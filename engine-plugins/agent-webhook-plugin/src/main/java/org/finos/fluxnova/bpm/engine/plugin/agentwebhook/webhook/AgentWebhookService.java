package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.webhook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.finos.fluxnova.bpm.engine.ExternalTaskService;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.AgentWebhookProperties;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.dto.LifecycleEventDto;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecution;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.persistence.AgentExecutionRepository;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.statemachine.AgentExecutionStateMachine;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.statemachine.IllegalAgentStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orchestrates one webhook delivery end to end: signature verification,
 * parsing, correlation, durable-before-ack persistence, and (only on a
 * terminal event that isn't blocked by a sequence gap) completing/failing
 * the underlying External Task. See design doc section 6.
 */
public class AgentWebhookService {

  private static final Logger LOG = LoggerFactory.getLogger(AgentWebhookService.class);

  public static final String VAR_BUSINESS_OUTCOME = "agentBusinessOutcome";
  public static final String VAR_RESPONSE = "agentResponse";

  private final AgentExecutionRepository repository;
  private final AgentExecutionStateMachine stateMachine;
  private final SignatureVerifier signatureVerifier;
  private final ExternalTaskService externalTaskService;
  private final AgentWebhookProperties properties;
  private final JsonMapper objectMapper = JsonMapper.builder().build();

  public AgentWebhookService(AgentExecutionRepository repository, AgentExecutionStateMachine stateMachine,
      SignatureVerifier signatureVerifier, ExternalTaskService externalTaskService,
      AgentWebhookProperties properties) {
    this.repository = repository;
    this.stateMachine = stateMachine;
    this.signatureVerifier = signatureVerifier;
    this.externalTaskService = externalTaskService;
    this.properties = properties;
  }

  public WebhookOutcome handleEvent(byte[] rawBody, String signatureHeader) {
    if (!signatureVerifier.isValid(properties.getWebhookSecret(), rawBody, signatureHeader)) {
      throw new InvalidWebhookRequestException("Invalid X-Lifecycle-Signature", 401);
    }

    LifecycleEventDto event = parse(rawBody);
    if (event.getId() == null || event.getType() == null) {
      throw new InvalidWebhookRequestException("Lifecycle event is missing 'id' or 'type'", 400);
    }
    if (event.getCorrelation() == null || event.getCorrelation().getExecutionId() == null) {
      throw new InvalidWebhookRequestException("Lifecycle event is missing correlation.executionId", 400);
    }

    String executionId = event.getCorrelation().getExecutionId();
    AgentExecution execution = repository.findByExecutionId(executionId)
        .orElseThrow(() -> new InvalidWebhookRequestException("Unknown Agent Execution: " + executionId, 404));

    Integer newSeq = event.getSeq();
    Integer lastSeq = execution.getLastSeq();
    // Strictly "<", not "<=": a replay of the exact last-applied seq (e.g. the same terminal event
    // redelivered) must still reach the event-id dedup check / state machine below, not be
    // short-circuited here - only a genuinely older seq is "superseded and safe to ignore".
    boolean isStale = lastSeq != null && newSeq != null && newSeq < lastSeq;

    if (isStale) {
      LOG.info("Stale/out-of-order event {} (seq {}) for execution {}; last applied seq is {} - recording only",
          event.getId(), newSeq, executionId, lastSeq);
      recordEventOnly(execution, event, rawBody);
      return WebhookOutcome.STALE_EVENT_RECORDED;
    }

    boolean introducesGap = lastSeq != null && newSeq != null && newSeq > lastSeq + 1;
    boolean gapOpen = execution.isSequenceGap() || introducesGap;

    AgentExecutionState nextState;
    try {
      nextState = stateMachine.nextState(execution.getState(), event.getType());
    } catch (IllegalAgentStateTransitionException e) {
      if (e.isDuplicateTerminal()) {
        LOG.info("Ignoring {} for execution {}: {}", event.getType(), executionId, e.getMessage());
        recordEventOnly(execution, event, rawBody);
        return WebhookOutcome.ALREADY_TERMINAL;
      }
      throw new InvalidWebhookRequestException(e.getMessage(), 409);
    }

    AgentExecution updated = copyWithTransition(execution, nextState, event, newSeq, gapOpen);

    boolean isNewEvent = repository.applyTransitionIfNewEvent(updated, event.getId(), newSeq, event.getType(),
        rawBody == null ? null : new String(rawBody, java.nio.charset.StandardCharsets.UTF_8));
    if (!isNewEvent) {
      LOG.info("Duplicate delivery of event {} for execution {} - already processed", event.getId(), executionId);
      return WebhookOutcome.DUPLICATE;
    }

    if (!nextState.isTerminal()) {
      return WebhookOutcome.ACCEPTED;
    }

    if (gapOpen) {
      LOG.warn("Execution {} reached terminal state {} with an open sequence gap - NOT completing/failing the "
          + "external task automatically; flagged for reconciliation", executionId, nextState);
      return WebhookOutcome.TERMINAL_BLOCKED_BY_GAP;
    }

    completeOrFailExternalTask(updated, event);
    return WebhookOutcome.ACCEPTED;
  }

  private LifecycleEventDto parse(byte[] rawBody) {
    try {
      return objectMapper.readValue(rawBody, LifecycleEventDto.class);
    } catch (RuntimeException e) {
      throw new InvalidWebhookRequestException("Malformed lifecycle event payload: " + e.getMessage(), 400);
    }
  }

  private void recordEventOnly(AgentExecution execution, LifecycleEventDto event, byte[] rawBody) {
    // Same row, no state/lastSeq change; still goes through the dedup-insert path so a retried
    // stale delivery is also idempotent.
    repository.applyTransitionIfNewEvent(execution, event.getId(), event.getSeq(), event.getType(),
        rawBody == null ? null : new String(rawBody, java.nio.charset.StandardCharsets.UTF_8));
  }

  private AgentExecution copyWithTransition(AgentExecution execution, AgentExecutionState nextState,
      LifecycleEventDto event, Integer newSeq, boolean gapOpen) {
    AgentExecution updated = new AgentExecution();
    updated.setExecutionId(execution.getExecutionId());
    updated.setProcessInstanceId(execution.getProcessInstanceId());
    updated.setActivityId(execution.getActivityId());
    updated.setActivityInstanceId(execution.getActivityInstanceId());
    updated.setExternalTaskId(execution.getExternalTaskId());
    updated.setAgentRef(execution.getAgentRef());
    updated.setSessionId(execution.getSessionId());
    updated.setCreatedAt(execution.getCreatedAt());

    updated.setState(nextState);
    updated.setLastEventId(event.getId());
    updated.setLastSeq(newSeq != null ? newSeq : execution.getLastSeq());
    updated.setSequenceGap(gapOpen);

    JsonNode data = event.getData();
    switch (event.getType()) {
      case LifecycleEventDto.TYPE_WAITING:
        updated.setWaitJson(data == null ? null : data.toString());
        updated.setBusinessOutcome(execution.getBusinessOutcome());
        updated.setResultJson(execution.getResultJson());
        updated.setErrorJson(execution.getErrorJson());
        break;
      case LifecycleEventDto.TYPE_COMPLETED:
        updated.setBusinessOutcome(textOrNull(data, "outcome"));
        updated.setResultJson(data == null ? null : data.toString());
        updated.setErrorJson(execution.getErrorJson());
        updated.setWaitJson(execution.getWaitJson());
        break;
      case LifecycleEventDto.TYPE_FAILED:
        updated.setErrorJson(data == null ? null : data.toString());
        updated.setBusinessOutcome(execution.getBusinessOutcome());
        updated.setResultJson(execution.getResultJson());
        updated.setWaitJson(execution.getWaitJson());
        break;
      default:
        updated.setBusinessOutcome(execution.getBusinessOutcome());
        updated.setResultJson(execution.getResultJson());
        updated.setErrorJson(execution.getErrorJson());
        updated.setWaitJson(execution.getWaitJson());
    }
    return updated;
  }

  private void completeOrFailExternalTask(AgentExecution updated, LifecycleEventDto event) {
    String workerId = properties.getWorkerId();
    try {
      if (updated.getState() == AgentExecutionState.COMPLETED) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(VAR_BUSINESS_OUTCOME, updated.getBusinessOutcome());
        variables.put(VAR_RESPONSE, textOrNull(event.getData(), "response"));
        externalTaskService.complete(updated.getExternalTaskId(), workerId, variables);
      } else if (updated.getState() == AgentExecutionState.FAILED) {
        String errorMessage = Optional.ofNullable(textOrNull(event.getData(), "error_message")).orElse("agent.failed");
        externalTaskService.handleFailure(updated.getExternalTaskId(), workerId, errorMessage,
            updated.getErrorJson(), properties.getFailureRetries(), properties.getFailureRetryTimeoutMillis());
      }
    } catch (RuntimeException e) {
      // The durable state transition already committed - that's the source of truth. A failure
      // here (e.g. the external task's lock already expired or it's gone) is logged for the
      // startup recovery sweep / ops to reconcile, not retried inline in the webhook request.
      LOG.error("Failed to {} external task {} for execution {}: {}",
          updated.getState() == AgentExecutionState.COMPLETED ? "complete" : "fail",
          updated.getExternalTaskId(), updated.getExecutionId(), e.getMessage(), e);
    }
  }

  /**
   * Read-only status list for a Monitoring diagram overlay: every Agent Execution for a process
   * instance, keyed by {@code activityId} with the waiting reason already unpacked from
   * {@code waitJson} - see the module README / WEBHOOK_INTEGRATION_DESIGN.md for the event shapes.
   */
  public List<AgentExecutionStatusDto> listByProcessInstance(String processInstanceId) {
    List<AgentExecutionStatusDto> result = new ArrayList<>();
    for (AgentExecution execution : repository.findByProcessInstanceId(processInstanceId)) {
      result.add(toStatusDto(execution));
    }
    return result;
  }

  private AgentExecutionStatusDto toStatusDto(AgentExecution execution) {
    AgentExecutionStatusDto dto = new AgentExecutionStatusDto();
    dto.setActivityId(execution.getActivityId());
    dto.setActivityInstanceId(execution.getActivityInstanceId());
    dto.setState(execution.getState().name());
    dto.setBusinessOutcome(execution.getBusinessOutcome());
    dto.setCreatedAt(execution.getCreatedAt());
    dto.setUpdatedAt(execution.getUpdatedAt());

    JsonNode wait = readTreeOrNull(execution.getWaitJson());
    if (wait != null) {
      dto.setWaitingTool(textOrNull(wait, "tool"));
      JsonNode args = wait.get("args");
      dto.setWaitingArgs(args == null || args.isNull() ? null : args.toString());
    }

    JsonNode error = readTreeOrNull(execution.getErrorJson());
    if (error != null) {
      dto.setErrorMessage(textOrNull(error, "error_message"));
    }
    return dto;
  }

  private JsonNode readTreeOrNull(String json) {
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readTree(json);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private String textOrNull(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

}
