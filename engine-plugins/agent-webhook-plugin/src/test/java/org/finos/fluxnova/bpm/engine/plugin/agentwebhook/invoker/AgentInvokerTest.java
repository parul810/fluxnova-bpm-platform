package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.invoker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.finos.fluxnova.bpm.engine.ExternalTaskService;
import org.finos.fluxnova.bpm.engine.externaltask.ExternalTaskQueryBuilder;
import org.finos.fluxnova.bpm.engine.externaltask.ExternalTaskQueryTopicBuilder;
import org.finos.fluxnova.bpm.engine.externaltask.LockedExternalTask;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.AgentWebhookProperties;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecution;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.persistence.AgentExecutionRepository;
import org.finos.fluxnova.bpm.engine.variable.Variables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentInvokerTest {

  private ExternalTaskService externalTaskService;
  private ExternalTaskQueryTopicBuilder topicBuilder;
  private AgentExecutionRepository repository;
  private AgentWebhookProperties properties;
  private RecordingClient adkClient;

  @BeforeEach
  void setUp() {
    externalTaskService = mock(ExternalTaskService.class);
    ExternalTaskQueryBuilder queryBuilder = mock(ExternalTaskQueryBuilder.class);
    topicBuilder = mock(ExternalTaskQueryTopicBuilder.class, Mockito.RETURNS_SELF);
    when(externalTaskService.fetchAndLock(anyInt(), anyString())).thenReturn(queryBuilder);
    when(queryBuilder.topic(anyString(), anyLong())).thenReturn(topicBuilder);

    repository = mock(AgentExecutionRepository.class);
    when(repository.findByExecutionId(anyString())).thenReturn(Optional.empty());

    properties = new AgentWebhookProperties();
    properties.setAdkBaseUrl("http://default-agent:8000");
    properties.setAppName("default_app");
    properties.setUserId("default-user");
    adkClient = new RecordingClient();
  }

  @Test
  void callsTheAgentNamedByTheTasksOwnExtensionProperties() {
    givenTask(Map.of("agent.baseUrl", "http://invoice-agent:9100", "agent.appName", "invoice_app",
        "agent.userId", "svc-invoices"), "Summarise invoice 42");

    invoker().poll();

    assertThat(adkClient.endpoints).hasSize(1);
    AgentEndpoint used = adkClient.endpoints.get(0);
    assertThat(used.baseUrl()).isEqualTo("http://invoice-agent:9100");
    assertThat(used.appName()).isEqualTo("invoice_app");
    assertThat(used.userId()).isEqualTo("svc-invoices");
    assertThat(adkClient.inputs).containsExactly("Summarise invoice 42");
    verify(topicBuilder).includeExtensionProperties();
    verify(externalTaskService, never()).handleFailure(anyString(), anyString(), anyString(), anyInt(), anyLong());
  }

  @Test
  void usesPluginDefaultsWhenTheTaskConfiguresNoAgent() {
    givenTask(Map.of(), "Create an expense report");

    invoker().poll();

    assertThat(adkClient.endpoints.get(0).baseUrl()).isEqualTo("http://default-agent:8000");
    assertThat(adkClient.endpoints.get(0).appName()).isEqualTo("default_app");
  }

  @Test
  void anUnsupportedProtocolFailsTheTaskWithoutCallingAnyAgent() {
    givenTask(Map.of("agent.protocol", "carrier-pigeon"), "hello");

    invoker().poll();

    assertThat(adkClient.endpoints).isEmpty();
    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(externalTaskService).handleFailure(eq("task-1"), anyString(), message.capture(), anyInt(), anyLong());
    assertThat(message.getValue()).contains("Unsupported agent.protocol 'carrier-pigeon'");

    ArgumentCaptor<AgentExecution> saved = ArgumentCaptor.forClass(AgentExecution.class);
    verify(repository, Mockito.atLeastOnce()).updateState(saved.capture());
    assertThat(saved.getValue().getState()).isEqualTo(AgentExecutionState.FAILED);
  }

  @Test
  void aDisallowedHostFailsTheTaskInsteadOfBeingCalled() {
    properties.setAllowedAgentHosts("trusted.internal");
    givenTask(Map.of("agent.baseUrl", "http://evil.example.com"), "hello");

    invoker().poll();

    assertThat(adkClient.endpoints).isEmpty();
    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(externalTaskService).handleFailure(eq("task-1"), anyString(), message.capture(), anyInt(), anyLong());
    assertThat(message.getValue()).contains("allowedAgentHosts");
  }

  private AgentInvoker invoker() {
    return new AgentInvoker(externalTaskService, Map.of("adk", adkClient), new AgentEndpointResolver(properties),
        repository, properties);
  }

  private void givenTask(Map<String, String> extensionProperties, String instruction) {
    LockedExternalTask task = mock(LockedExternalTask.class);
    when(task.getId()).thenReturn("task-1");
    when(task.getProcessInstanceId()).thenReturn("pi-1");
    when(task.getActivityId()).thenReturn("AgentTask_1");
    when(task.getActivityInstanceId()).thenReturn("ai-1");
    when(task.getTopicName()).thenReturn("fluxnova-agent");
    when(task.getExtensionProperties()).thenReturn(extensionProperties);
    when(task.getVariables()).thenReturn(Variables.createVariables().putValue("agentInputText", instruction));
    when(topicBuilder.execute()).thenReturn(List.of(task));
  }

  private static class RecordingClient implements AgentClient {
    final List<AgentEndpoint> endpoints = new ArrayList<>();
    final List<String> inputs = new ArrayList<>();

    @Override
    public void createSession(AgentEndpoint endpoint, String sessionId, String processInstanceId,
        String activityInstanceId, String executionId) {
      endpoints.add(endpoint);
    }

    @Override
    public void run(AgentEndpoint endpoint, String sessionId, String inputText) {
      inputs.add(inputText);
    }
  }

}
