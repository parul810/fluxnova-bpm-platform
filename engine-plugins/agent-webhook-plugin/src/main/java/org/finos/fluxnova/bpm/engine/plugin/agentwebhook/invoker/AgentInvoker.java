package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.invoker;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.finos.fluxnova.bpm.engine.ExternalTaskService;
import org.finos.fluxnova.bpm.engine.externaltask.LockedExternalTask;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.AgentWebhookProperties;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecution;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.persistence.AgentExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches-and-locks the configured topic and, for each new task, resolves which
 * agent to call from the task's own extension properties, starts a session and
 * sends the first message. Deliberately does not complete the
 * task afterwards - see design doc section 5. A task that is fetched again
 * while it already has a non-terminal {@link AgentExecution} (its lock
 * expired before a terminal webhook event arrived) is treated as a waiting
 * timeout: the lock is extended and the task is left as-is rather than
 * invoking the agent a second time, per design doc section 16 ("do not
 * create a new external Agent Execution for every retry").
 */
public class AgentInvoker implements Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(AgentInvoker.class);

  private final ExternalTaskService externalTaskService;
  private final Map<String, AgentClient> clientsByProtocol;
  private final AgentEndpointResolver endpointResolver;
  private final AgentExecutionRepository repository;
  private final AgentWebhookProperties properties;

  public AgentInvoker(ExternalTaskService externalTaskService, Map<String, AgentClient> clientsByProtocol,
      AgentEndpointResolver endpointResolver, AgentExecutionRepository repository,
      AgentWebhookProperties properties) {
    this.externalTaskService = externalTaskService;
    this.clientsByProtocol = clientsByProtocol;
    this.endpointResolver = endpointResolver;
    this.repository = repository;
    this.properties = properties;
  }

  @Override
  public void run() {
    try {
      poll();
    } catch (RuntimeException e) {
      LOG.error("agent-webhook-plugin invoker poll failed", e);
    }
  }

  void poll() {
    List<LockedExternalTask> tasks = externalTaskService
        .fetchAndLock(properties.getMaxTasksPerPoll(), properties.getWorkerId())
        .topic(properties.getTopicName(), properties.getLockDurationMillis())
        .variables("agentInputText", "amount", "reason")
        .includeExtensionProperties()
        .execute();

    for (LockedExternalTask task : tasks) {
      handle(task);
    }
  }

  private void handle(LockedExternalTask task) {
    Optional<AgentExecution> existing = repository.findByExecutionId(task.getId());
    if (existing.isPresent() && !existing.get().getState().isTerminal()) {
      LOG.warn("External task {} was re-fetched while Agent Execution is still {} - treating as a waiting "
          + "timeout, extending the lock without re-invoking the agent", task.getId(), existing.get().getState());
      externalTaskService.extendLock(task.getId(), properties.getWorkerId(), properties.getLockDurationMillis());
      return;
    }
    if (existing.isPresent() && existing.get().getState() == AgentExecutionState.FAILED) {
      // An operator retried this external task (e.g. reset its retries) after the prior invocation
      // attempt failed. Start a clean logical execution for it rather than silently treating the
      // old FAILED row as "already terminal, nothing to do" forever - see design doc section 16.
      LOG.info("External task {} was retried after a prior FAILED invocation - starting a fresh Agent Execution",
          task.getId());
      repository.deleteByExecutionId(task.getId());
    } else if (existing.isPresent()) {
      // COMPLETED/CANCELLED and still fetchable is unexpected (a completed external task shouldn't
      // be fetchable again) - log it as an anomaly rather than silently re-invoking the agent, which
      // could duplicate an already-finished business outcome.
      LOG.error("External task {} was re-fetched but its Agent Execution is already {} - not re-invoking the "
          + "agent; this needs manual investigation", task.getId(), existing.get().getState());
      return;
    }

    AgentExecution execution = new AgentExecution();
    execution.setExecutionId(task.getId());
    execution.setExternalTaskId(task.getId());
    execution.setProcessInstanceId(task.getProcessInstanceId());
    execution.setActivityId(task.getActivityId());
    execution.setActivityInstanceId(task.getActivityInstanceId());
    execution.setAgentRef(task.getTopicName());
    execution.setSessionId(task.getId());
    execution.setState(AgentExecutionState.STARTING);
    repository.insert(execution);

    try {
      String inputText = buildInputText(task);
      AgentEndpoint endpoint = endpointResolver.resolve(task.getExtensionProperties());
      AgentClient client = clientFor(endpoint);
      client.createSession(endpoint, task.getId(), task.getProcessInstanceId(), task.getActivityInstanceId(),
          task.getId());
      client.run(endpoint, task.getId(), inputText);

      // The agent's webhooks can land before /run returns, so only STARTING may become RUNNING here;
      // a state the webhooks already set (WAITING, COMPLETED, ...) must stand.
      repository.markRunningIfStarting(task.getId());
    } catch (RuntimeException e) {
      LOG.error("Failed to invoke agent for external task {}: {}", task.getId(), e.getMessage(), e);
      execution.setState(AgentExecutionState.FAILED);
      execution.setErrorJson("{\"stage\":\"invocation\",\"error_message\":\"" + escapeJson(e.getMessage()) + "\"}");
      repository.updateState(execution);
      externalTaskService.handleFailure(task.getId(), properties.getWorkerId(),
          "Agent invocation failed: " + e.getMessage(), properties.getFailureRetries(),
          properties.getFailureRetryTimeoutMillis());
    }
  }

  private AgentClient clientFor(AgentEndpoint endpoint) {
    AgentClient client = clientsByProtocol.get(endpoint.protocol());
    if (client == null) {
      throw new AgentInvocationException("Unsupported agent.protocol '" + endpoint.protocol()
          + "'; supported: " + clientsByProtocol.keySet());
    }
    return client;
  }

  private String buildInputText(LockedExternalTask task) {
    Object explicit = task.getVariables().get("agentInputText");
    if (explicit instanceof String text && !text.isBlank()) {
      return text;
    }
    Object amount = task.getVariables().get("amount");
    Object reason = task.getVariables().get("reason");
    if (amount != null && reason != null) {
      return "Request approval for an expense of $" + amount + " for " + reason + ".";
    }
    throw new AgentInvocationException(
        "External task " + task.getId() + " has neither 'agentInputText' nor both 'amount' and 'reason' set");
  }

  private String escapeJson(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

}
