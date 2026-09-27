package org.finos.fluxnova.bpm.engine.plugin.agentwebhook;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.finos.fluxnova.bpm.engine.ExternalTaskService;
import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.finos.fluxnova.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.finos.fluxnova.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.invoker.AdkAgentClient;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.invoker.AgentClient;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.invoker.AgentEndpointResolver;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.invoker.AgentInvoker;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.persistence.AgentExecutionRepository;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.persistence.JdbcAgentExecutionRepository;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.persistence.SchemaInitializer;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.statemachine.AgentExecutionStateMachine;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.webhook.AgentWebhookResource;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.webhook.AgentWebhookService;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.webhook.SignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires the webhook lifecycle integration into the process engine: applies
 * the plugin's own schema (section 7/9 of the design doc), starts the
 * External Task invoker poller (section 5), and publishes the shared
 * {@link AgentWebhookService} for {@link AgentWebhookResource} to use
 * (section 6).
 *
 * <p>Configure via the bean's setters, either through {@code processes.xml}
 * plugin properties or by setting them directly on the Spring-managed bean -
 * see the module README.
 *
 * <p><strong>Known limitation:</strong> a crash between committing a terminal
 * state transition and the subsequent {@code ExternalTaskService.complete}/
 * {@code handleFailure} call is logged but not yet actively reconciled by a
 * startup sweep (design doc section 6 "Recovery after restart"). The
 * duplicate-terminal-event guard in {@link AgentExecutionStateMachine}
 * prevents a second automatic completion once the gap is noticed manually.
 */
public class AgentWebhookProcessEnginePlugin extends AbstractProcessEnginePlugin {

  private static final Logger LOG = LoggerFactory.getLogger(AgentWebhookProcessEnginePlugin.class);

  private final AgentWebhookProperties properties = new AgentWebhookProperties();

  private ScheduledExecutorService scheduler;

  @Override
  public void postProcessEngineBuild(ProcessEngine processEngine) {
    ProcessEngineConfigurationImpl configuration =
        (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    DataSource dataSource = configuration.getDataSource();

    new SchemaInitializer().run(dataSource);

    AgentExecutionRepository repository = new JdbcAgentExecutionRepository(dataSource);
    ExternalTaskService externalTaskService = processEngine.getExternalTaskService();

    AgentWebhookService webhookService = new AgentWebhookService(repository, new AgentExecutionStateMachine(),
        new SignatureVerifier(), externalTaskService, properties);
    AgentWebhookResource.setService(webhookService);

    Map<String, AgentClient> clients = Map.of(AgentEndpointResolver.DEFAULT_PROTOCOL, new AdkAgentClient(properties));
    AgentInvoker invoker = new AgentInvoker(externalTaskService, clients, new AgentEndpointResolver(properties),
        repository, properties);

    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "agent-webhook-plugin-invoker");
      t.setDaemon(true);
      return t;
    });
    scheduler.scheduleWithFixedDelay(invoker, 0, properties.getPollIntervalMillis(), TimeUnit.MILLISECONDS);

    LOG.info("agent-webhook-plugin started: topic={}, pollIntervalMillis={}", properties.getTopicName(),
        properties.getPollIntervalMillis());
  }

  public void shutdown() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  public AgentWebhookProperties getProperties() {
    return properties;
  }

  public void setTopicName(String topicName) {
    properties.setTopicName(topicName);
  }

  public void setAdkBaseUrl(String adkBaseUrl) {
    properties.setAdkBaseUrl(adkBaseUrl);
  }

  public void setAppName(String appName) {
    properties.setAppName(appName);
  }

  public void setUserId(String userId) {
    properties.setUserId(userId);
  }

  public void setWebhookCallbackUrl(String webhookCallbackUrl) {
    properties.setWebhookCallbackUrl(webhookCallbackUrl);
  }

  public void setWebhookSecret(String webhookSecret) {
    properties.setWebhookSecret(webhookSecret);
  }

  public void setWorkerId(String workerId) {
    properties.setWorkerId(workerId);
  }

  public void setLockDurationMillis(long lockDurationMillis) {
    properties.setLockDurationMillis(lockDurationMillis);
  }

  public void setPollIntervalMillis(long pollIntervalMillis) {
    properties.setPollIntervalMillis(pollIntervalMillis);
  }

  public void setMaxTasksPerPoll(int maxTasksPerPoll) {
    properties.setMaxTasksPerPoll(maxTasksPerPoll);
  }

  public void setFailureRetries(int failureRetries) {
    properties.setFailureRetries(failureRetries);
  }

  public void setFailureRetryTimeoutMillis(long failureRetryTimeoutMillis) {
    properties.setFailureRetryTimeoutMillis(failureRetryTimeoutMillis);
  }

  public void setInvocationTimeoutMillis(long invocationTimeoutMillis) {
    properties.setInvocationTimeoutMillis(invocationTimeoutMillis);
  }

  public void setAllowedAgentHosts(String commaSeparatedHosts) {
    properties.setAllowedAgentHosts(commaSeparatedHosts);
  }

}
