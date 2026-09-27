package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.invoker;

import java.util.Map;

import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.AgentWebhookProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentEndpointResolverTest {

  private AgentWebhookProperties defaults;
  private AgentEndpointResolver resolver;

  @BeforeEach
  void setUp() {
    defaults = new AgentWebhookProperties();
    defaults.setAdkBaseUrl("http://default-agent:8000");
    defaults.setAppName("default_app");
    defaults.setUserId("default-user");
    resolver = new AgentEndpointResolver(defaults);
  }

  @Test
  void taskPropertiesOverrideEveryDefault() {
    AgentEndpoint endpoint = resolver.resolve(Map.of(
        "agent.baseUrl", "https://agents.example.com:9000",
        "agent.appName", "invoice_agent",
        "agent.userId", "svc-invoices",
        "agent.protocol", "ADK"));

    assertThat(endpoint.baseUrl()).isEqualTo("https://agents.example.com:9000");
    assertThat(endpoint.appName()).isEqualTo("invoice_agent");
    assertThat(endpoint.userId()).isEqualTo("svc-invoices");
    assertThat(endpoint.protocol()).isEqualTo("adk");
  }

  @Test
  void fallsBackToPluginDefaultsWhenTaskSetsNothing() {
    AgentEndpoint endpoint = resolver.resolve(Map.of());

    assertThat(endpoint.baseUrl()).isEqualTo("http://default-agent:8000");
    assertThat(endpoint.appName()).isEqualTo("default_app");
    assertThat(endpoint.userId()).isEqualTo("default-user");
    assertThat(endpoint.protocol()).isEqualTo("adk");
  }

  @Test
  void toleratesNullAndBlankValues() {
    assertThat(resolver.resolve(null).baseUrl()).isEqualTo("http://default-agent:8000");
    assertThat(resolver.resolve(Map.of("agent.baseUrl", "   ")).baseUrl()).isEqualTo("http://default-agent:8000");
  }

  @Test
  void stripsTrailingSlashSoPathsJoinCleanly() {
    assertThat(resolver.resolve(Map.of("agent.baseUrl", "http://a:1/")).baseUrl()).isEqualTo("http://a:1");
  }

  @Test
  void rejectsNonHttpSchemesAndMalformedUrls() {
    assertThrows(AgentInvocationException.class, () -> resolver.resolve(Map.of("agent.baseUrl", "file:///etc/passwd")));
    assertThrows(AgentInvocationException.class, () -> resolver.resolve(Map.of("agent.baseUrl", "not a url")));
    assertThrows(AgentInvocationException.class, () -> resolver.resolve(Map.of("agent.baseUrl", "http://")));
  }

  @Test
  void allowListRestrictsTaskChosenHosts() {
    defaults.setAllowedAgentHosts("localhost, Agents.Example.com");

    assertThat(resolver.resolve(Map.of("agent.baseUrl", "http://localhost:8000")).baseUrl())
        .isEqualTo("http://localhost:8000");
    assertThat(resolver.resolve(Map.of("agent.baseUrl", "https://agents.example.com")).baseUrl())
        .isEqualTo("https://agents.example.com");

    AgentInvocationException e = assertThrows(AgentInvocationException.class,
        () -> resolver.resolve(Map.of("agent.baseUrl", "http://169.254.169.254")));
    assertThat(e.getMessage()).contains("allowedAgentHosts");
  }

}
