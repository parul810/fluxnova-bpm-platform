package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.webhook;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.finos.fluxnova.bpm.engine.ExternalTaskService;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.AgentWebhookProperties;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecution;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.statemachine.AgentExecutionStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AgentWebhookServiceTest {

  private static final String EXECUTION_ID = "task-1";

  private FakeAgentExecutionRepository repository;
  private ExternalTaskService externalTaskService;
  private AgentWebhookProperties properties;
  private AgentWebhookService service;

  @BeforeEach
  void setUp() {
    repository = new FakeAgentExecutionRepository();
    externalTaskService = mock(ExternalTaskService.class);
    properties = new AgentWebhookProperties();
    properties.setWebhookSecret(null); // signature verification not under test here
    service = new AgentWebhookService(repository, new AgentExecutionStateMachine(), new SignatureVerifier(),
        externalTaskService, properties);

    AgentExecution execution = new AgentExecution();
    execution.setExecutionId(EXECUTION_ID);
    execution.setExternalTaskId(EXECUTION_ID);
    execution.setProcessInstanceId("pi-100");
    execution.setActivityInstanceId("ai-200");
    execution.setState(AgentExecutionState.STARTING);
    repository.insert(execution);
  }

  @Test
  void fullApprovedLifecycleCompletesExternalTaskWithMappedOutcome() {
    assertThat(service.handleEvent(event("evt-1", 1, "agent.started", null), null)).isEqualTo(WebhookOutcome.ACCEPTED);
    assertThat(service.handleEvent(event("evt-2", 2, "agent.waiting",
        "\"tool\":\"request_approval\",\"args\":{\"amount\":250}"), null)).isEqualTo(WebhookOutcome.ACCEPTED);
    assertThat(service.handleEvent(event("evt-3", 3, "agent.resumed", null), null)).isEqualTo(WebhookOutcome.ACCEPTED);

    WebhookOutcome outcome = service.handleEvent(
        event("evt-4", 4, "agent.completed", "\"outcome\":\"approved\",\"response\":\"Approved.\""), null);

    assertThat(outcome).isEqualTo(WebhookOutcome.ACCEPTED);
    assertThat(repository.findByExecutionId(EXECUTION_ID).orElseThrow().getState())
        .isEqualTo(AgentExecutionState.COMPLETED);
    verify(externalTaskService).complete(eq(EXECUTION_ID), anyString(), any());
  }

  @Test
  void completedEventMapsOutcomeAndResponseIntoProcessVariables() {
    service.handleEvent(event("evt-1", 1, "agent.started", null), null);
    service.handleEvent(
        event("evt-2", 2, "agent.completed", "\"outcome\":\"rejected\",\"response\":\"Not approved.\""), null);

    verify(externalTaskService).complete(eq(EXECUTION_ID), anyString(), argThatVariables(vars -> {
      assertThat(vars).containsEntry(AgentWebhookService.VAR_BUSINESS_OUTCOME, "rejected");
      assertThat(vars).containsEntry(AgentWebhookService.VAR_RESPONSE, "Not approved.");
    }));
  }

  @Test
  void failedEventCallsHandleFailure() {
    service.handleEvent(event("evt-1", 1, "agent.started", null), null);
    service.handleEvent(event("evt-2", 2, "agent.failed",
        "\"stage\":\"tool\",\"error_type\":\"Timeout\",\"error_message\":\"downstream timed out\""), null);

    assertThat(repository.findByExecutionId(EXECUTION_ID).orElseThrow().getState())
        .isEqualTo(AgentExecutionState.FAILED);
    verify(externalTaskService).handleFailure(eq(EXECUTION_ID), anyString(), eq("downstream timed out"), anyString(),
        anyInt(), anyLong());
  }

  @Test
  void duplicateNonTerminalEventIdIsAcknowledgedWithoutRepeatingSideEffects() {
    service.handleEvent(event("evt-1", 1, "agent.started", null), null);

    // Same event id AND seq redelivered while still non-terminal - the state machine allows
    // re-applying agent.started (idempotent), so the true dedup happens at the event-id level.
    WebhookOutcome replay = service.handleEvent(event("evt-1", 1, "agent.started", null), null);

    assertThat(replay).isEqualTo(WebhookOutcome.DUPLICATE);
    assertThat(repository.findByExecutionId(EXECUTION_ID).orElseThrow().getState())
        .isEqualTo(AgentExecutionState.RUNNING);
  }

  @Test
  void duplicateTerminalEventIsAcknowledgedWithoutCompletingTwice() {
    service.handleEvent(event("evt-1", 1, "agent.started", null), null);
    service.handleEvent(event("evt-2", 2, "agent.completed", "\"outcome\":\"approved\""), null);

    // Exact same terminal event redelivered - caught by the state machine's "already terminal"
    // guard before it would even reach the event-id dedup check; equally safe/no-op either way.
    WebhookOutcome replay = service.handleEvent(event("evt-2", 2, "agent.completed", "\"outcome\":\"approved\""),
        null);

    assertThat(replay).isEqualTo(WebhookOutcome.ALREADY_TERMINAL);
    verify(externalTaskService, times(1)).complete(eq(EXECUTION_ID), anyString(), any());
  }

  @Test
  void lateTerminalEventAfterCompletionIsIgnored() {
    service.handleEvent(event("evt-1", 1, "agent.started", null), null);
    service.handleEvent(event("evt-2", 2, "agent.completed", "\"outcome\":\"approved\""), null);

    WebhookOutcome lateFailure = service.handleEvent(event("evt-3", 3, "agent.failed", "\"error_message\":\"late\""),
        null);

    assertThat(lateFailure).isEqualTo(WebhookOutcome.ALREADY_TERMINAL);
    verify(externalTaskService, never()).handleFailure(anyString(), anyString(), anyString(), anyString(), anyInt(),
        anyLong());
  }

  @Test
  void outOfOrderStaleEventIsRecordedButDoesNotRegressState() {
    service.handleEvent(event("evt-1", 1, "agent.started", null), null);
    service.handleEvent(event("evt-3", 3, "agent.resumed", null), null);

    WebhookOutcome stale = service.handleEvent(event("evt-2", 2, "agent.waiting", null), null);

    assertThat(stale).isEqualTo(WebhookOutcome.STALE_EVENT_RECORDED);
    assertThat(repository.findByExecutionId(EXECUTION_ID).orElseThrow().getState())
        .isEqualTo(AgentExecutionState.RUNNING);
  }

  @Test
  void sequenceGapBlocksAutomaticTerminalCompletion() {
    service.handleEvent(event("evt-1", 1, "agent.started", null), null);
    // seq 3 arrives, skipping seq 2 entirely.
    WebhookOutcome outcome = service.handleEvent(event("evt-3", 3, "agent.completed", "\"outcome\":\"approved\""),
        null);

    assertThat(outcome).isEqualTo(WebhookOutcome.TERMINAL_BLOCKED_BY_GAP);
    verify(externalTaskService, never()).complete(anyString(), anyString(), any());
    assertThat(repository.findByExecutionId(EXECUTION_ID).orElseThrow().isSequenceGap()).isTrue();
  }

  @Test
  void unknownExecutionIsRejectedWithNotFound() {
    byte[] payload = event("evt-1", 1, "agent.started", null, "unknown-execution");
    InvalidWebhookRequestException e = assertThrows(InvalidWebhookRequestException.class,
        () -> service.handleEvent(payload, null));
    assertThat(e.getStatusCode()).isEqualTo(404);
  }

  @Test
  void invalidSignatureIsRejected() {
    properties.setWebhookSecret("topsecret");
    byte[] payload = event("evt-1", 1, "agent.started", null);

    InvalidWebhookRequestException e = assertThrows(InvalidWebhookRequestException.class,
        () -> service.handleEvent(payload, "sha256=wrong"));
    assertThat(e.getStatusCode()).isEqualTo(401);
  }

  private byte[] event(String id, int seq, String type, String dataFields) {
    return event(id, seq, type, dataFields, EXECUTION_ID);
  }

  private byte[] event(String id, int seq, String type, String dataFields, String executionId) {
    String data = dataFields == null ? "{}" : "{" + dataFields + "}";
    String json = "{"
        + "\"id\":\"" + id + "\","
        + "\"seq\":" + seq + ","
        + "\"type\":\"" + type + "\","
        + "\"source\":\"human_in_the_loop\","
        + "\"session_id\":\"" + EXECUTION_ID + "\","
        + "\"correlation\":{"
        + "  \"processInstanceId\":\"pi-100\","
        + "  \"activityInstanceId\":\"ai-200\","
        + "  \"executionId\":\"" + executionId + "\""
        + "},"
        + "\"data\":" + data
        + "}";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> argThatVariables(java.util.function.Consumer<Map<String, Object>> assertion) {
    return org.mockito.ArgumentMatchers.argThat(vars -> {
      assertion.accept(vars);
      return true;
    });
  }

}
