package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.invoker;

/**
 * Where and how to reach one agent, resolved per external task from its
 * extension properties (falling back to the plugin's defaults).
 *
 * @param baseUrl  agent server base URL, no trailing slash
 * @param appName  application name on the agent server
 * @param userId   user id passed to the agent's session and run calls
 * @param protocol which {@link AgentClient} speaks to it, e.g. {@code adk}
 */
public record AgentEndpoint(String baseUrl, String appName, String userId, String protocol) {
}
