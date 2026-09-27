package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.persistence;

import java.util.List;
import java.util.Optional;

import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecution;

/**
 * Persistence port for {@link AgentExecution} rows and their processed-event
 * dedup log. Kept independent of Camunda's own {@code DbEntityManager} /
 * MyBatis registry - see design doc section 7 for why.
 */
public interface AgentExecutionRepository {

  void insert(AgentExecution execution);

  Optional<AgentExecution> findByExecutionId(String executionId);

  /**
   * Lists every Agent Execution for a process instance - used by the read-only status endpoint
   * that a Monitoring diagram overlay polls to show live state per activity.
   */
  List<AgentExecution> findByProcessInstanceId(String processInstanceId);

  /**
   * Plain state/error update with no event-log entry - used by the invoker for its own optimistic
   * bookkeeping (design doc section 5), as opposed to {@link #applyTransitionIfNewEvent} which is
   * reserved for genuine, deduplicated webhook deliveries.
   */
  void updateState(AgentExecution execution);

  /**
   * Moves an execution from {@code STARTING} to {@code RUNNING}, and only from {@code STARTING}. The
   * invoker uses this after {@code /run} returns; by then the agent's own webhooks may already have
   * advanced the execution (e.g. to {@code WAITING}), and that must not be overwritten.
   *
   * @return true if the execution was still {@code STARTING} and is now {@code RUNNING}
   */
  boolean markRunningIfStarting(String executionId);

  /**
   * Removes an execution and its event log - used when an operator retries an external task whose
   * prior invocation attempt {@code FAILED}, so the retry starts a clean logical execution rather
   * than being silently ignored as "already terminal" (design doc section 16, retry safety).
   */
  void deleteByExecutionId(String executionId);

  /**
   * Applies a state transition and records the delivered event as processed,
   * atomically. Returns {@code false} without changing anything if
   * {@code eventId} was already processed for this execution (the delivery
   * is a duplicate) - the caller should still acknowledge the webhook in
   * that case.
   */
  boolean applyTransitionIfNewEvent(AgentExecution updated, String eventId, Integer seq, String eventType,
      String rawPayload);

}
