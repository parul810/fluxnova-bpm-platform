package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.webhook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecution;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.persistence.AgentExecutionRepository;

/** In-memory test double mirroring the real JDBC repository's dedup-by-event-id semantics. */
class FakeAgentExecutionRepository implements AgentExecutionRepository {

  private final Map<String, AgentExecution> byExecutionId = new HashMap<>();
  private final Set<String> processedEventIds = new HashSet<>();

  @Override
  public void insert(AgentExecution execution) {
    byExecutionId.put(execution.getExecutionId(), execution);
  }

  @Override
  public Optional<AgentExecution> findByExecutionId(String executionId) {
    return Optional.ofNullable(byExecutionId.get(executionId));
  }

  @Override
  public List<AgentExecution> findByProcessInstanceId(String processInstanceId) {
    List<AgentExecution> result = new ArrayList<>();
    for (AgentExecution execution : byExecutionId.values()) {
      if (processInstanceId.equals(execution.getProcessInstanceId())) {
        result.add(execution);
      }
    }
    return result;
  }

  @Override
  public void updateState(AgentExecution execution) {
    byExecutionId.put(execution.getExecutionId(), execution);
  }

  @Override
  public boolean markRunningIfStarting(String executionId) {
    AgentExecution execution = byExecutionId.get(executionId);
    if (execution == null || execution.getState() != org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState.STARTING) {
      return false;
    }
    execution.setState(org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState.RUNNING);
    return true;
  }

  @Override
  public void deleteByExecutionId(String executionId) {
    byExecutionId.remove(executionId);
  }

  @Override
  public boolean applyTransitionIfNewEvent(AgentExecution updated, String eventId, Integer seq, String eventType,
      String rawPayload) {
    if (!processedEventIds.add(eventId)) {
      return false;
    }
    byExecutionId.put(updated.getExecutionId(), updated);
    return true;
  }

}
