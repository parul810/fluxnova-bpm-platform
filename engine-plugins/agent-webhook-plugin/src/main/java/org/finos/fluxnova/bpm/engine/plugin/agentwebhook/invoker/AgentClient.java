package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.invoker;

/**
 * The invoke half of an agent integration: start a session and send it the
 * first message. Everything after that is reported back through lifecycle
 * webhooks, so an implementation only has to know how to start a run for one
 * agent API.
 */
public interface AgentClient {

  void createSession(AgentEndpoint endpoint, String sessionId, String processInstanceId,
      String activityInstanceId, String executionId);

  void run(AgentEndpoint endpoint, String sessionId, String inputText);

}
