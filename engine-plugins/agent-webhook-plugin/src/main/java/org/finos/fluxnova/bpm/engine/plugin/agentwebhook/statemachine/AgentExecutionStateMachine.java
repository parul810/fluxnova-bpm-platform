package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.statemachine;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.dto.LifecycleEventDto;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState;

import static org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState.*;

/**
 * Pure, side-effect-free validation of lifecycle-event-driven state
 * transitions. See design doc section 4 for the transition diagram.
 *
 * <p>{@code agent.started} and {@code agent.resumed} are treated as
 * idempotent no-ops when the execution is already {@code RUNNING} - the
 * invoker's own optimistic {@code RUNNING} write (design doc section 5) and
 * the {@code agent.started} webhook race benignly, whichever arrives first.
 */
public class AgentExecutionStateMachine {

  private static final Map<String, Set<AgentExecutionState>> ALLOWED_FROM = Map.of(
      LifecycleEventDto.TYPE_STARTED, EnumSet.of(CREATED, STARTING, RUNNING),
      LifecycleEventDto.TYPE_WAITING, EnumSet.of(RUNNING, WAITING),
      LifecycleEventDto.TYPE_RESUMED, EnumSet.of(WAITING, RUNNING),
      LifecycleEventDto.TYPE_COMPLETED, EnumSet.of(CREATED, STARTING, RUNNING, WAITING),
      LifecycleEventDto.TYPE_FAILED, EnumSet.of(CREATED, STARTING, RUNNING, WAITING));

  private static final Map<String, AgentExecutionState> TARGET_STATE = Map.of(
      LifecycleEventDto.TYPE_STARTED, RUNNING,
      LifecycleEventDto.TYPE_WAITING, WAITING,
      LifecycleEventDto.TYPE_RESUMED, RUNNING,
      LifecycleEventDto.TYPE_COMPLETED, COMPLETED,
      LifecycleEventDto.TYPE_FAILED, FAILED);

  /**
   * @return the state the execution should move to for the given event type
   * @throws IllegalAgentStateTransitionException if the event type is unknown
   *         or is not a valid transition from {@code current}
   */
  public AgentExecutionState nextState(AgentExecutionState current, String eventType) {
    Set<AgentExecutionState> allowedFrom = ALLOWED_FROM.get(eventType);
    if (allowedFrom == null) {
      throw new IllegalAgentStateTransitionException("Unknown lifecycle event type: " + eventType, false);
    }
    if (current != null && current.isTerminal()) {
      throw IllegalAgentStateTransitionException.alreadyTerminal(current, eventType);
    }
    if (!allowedFrom.contains(current)) {
      throw IllegalAgentStateTransitionException.notAllowed(current, eventType);
    }
    return TARGET_STATE.get(eventType);
  }

}
