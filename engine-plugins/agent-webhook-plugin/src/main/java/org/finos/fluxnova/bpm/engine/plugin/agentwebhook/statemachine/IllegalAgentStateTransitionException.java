package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.statemachine;

import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState;

/**
 * Thrown when an event type is not a valid transition from the execution's
 * current state. {@link #duplicateTerminal} distinguishes "this execution
 * already reached a terminal state" (safe to ignore - the delivery is a
 * duplicate or a late retry) from a genuine anomaly.
 */
public class IllegalAgentStateTransitionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final boolean duplicateTerminal;

  public IllegalAgentStateTransitionException(String message, boolean duplicateTerminal) {
    super(message);
    this.duplicateTerminal = duplicateTerminal;
  }

  public boolean isDuplicateTerminal() {
    return duplicateTerminal;
  }

  public static IllegalAgentStateTransitionException alreadyTerminal(AgentExecutionState current, String eventType) {
    return new IllegalAgentStateTransitionException(
        "Execution is already in terminal state " + current + "; ignoring late/duplicate event " + eventType, true);
  }

  public static IllegalAgentStateTransitionException notAllowed(AgentExecutionState current, String eventType) {
    return new IllegalAgentStateTransitionException(
        "Event " + eventType + " is not a valid transition from state " + current, false);
  }

}
