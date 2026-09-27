package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model;

/**
 * Normalized lifecycle state of one Agent Execution, as defined by the
 * webhook integration design (see {@code WEBHOOK_INTEGRATION_DESIGN.md} section 4).
 */
public enum AgentExecutionState {

  CREATED,
  STARTING,
  RUNNING,
  WAITING,
  COMPLETED,
  FAILED,
  CANCELLED;

  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED;
  }

}
