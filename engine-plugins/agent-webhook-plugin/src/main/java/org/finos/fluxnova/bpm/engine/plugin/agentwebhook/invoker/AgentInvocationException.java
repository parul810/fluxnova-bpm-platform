package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.invoker;

/** Raised when session creation or {@code /run} fails before a usable execution is established. */
public class AgentInvocationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AgentInvocationException(String message, Throwable cause) {
    super(message, cause);
  }

  public AgentInvocationException(String message) {
    super(message);
  }

}
