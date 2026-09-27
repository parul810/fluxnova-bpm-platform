package org.finos.fluxnova.bpm.engine.plugin.agentwebhook;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Plain setter-based configuration bean, populated the same way other
 * {@code ProcessEnginePlugin}s in this codebase are configured (via
 * {@code processes.xml}/{@code bpm-platform.xml} plugin properties, or by
 * setting the properties on the Spring-managed plugin bean directly).
 *
 * <p>See the module README for the full list and defaults.
 */
public class AgentWebhookProperties {

  public static final String DEFAULT_WORKER_ID = "agent-webhook-plugin";

  private String topicName = "fluxnova-agent";
  private String adkBaseUrl = "http://localhost:8000";
  private String appName = "human_in_the_loop";
  private String userId = "workx-service-account";
  private String webhookCallbackUrl;
  private String webhookSecret;
  private String workerId = DEFAULT_WORKER_ID;
  private long lockDurationMillis = 24L * 60 * 60 * 1000; // 24h; doubles as the waiting-timeout, see design doc s.5
  private long pollIntervalMillis = 2000L;
  private int maxTasksPerPoll = 10;
  private int failureRetries = 0;
  private long failureRetryTimeoutMillis = 0L;
  private long invocationTimeoutMillis = 30_000L;
  private Set<String> allowedAgentHosts = Set.of();

  public String getTopicName() {
    return topicName;
  }

  public void setTopicName(String topicName) {
    this.topicName = topicName;
  }

  public String getAdkBaseUrl() {
    return adkBaseUrl;
  }

  public void setAdkBaseUrl(String adkBaseUrl) {
    this.adkBaseUrl = adkBaseUrl;
  }

  public String getAppName() {
    return appName;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getWebhookCallbackUrl() {
    return webhookCallbackUrl;
  }

  public void setWebhookCallbackUrl(String webhookCallbackUrl) {
    this.webhookCallbackUrl = webhookCallbackUrl;
  }

  public String getWebhookSecret() {
    return webhookSecret;
  }

  public void setWebhookSecret(String webhookSecret) {
    this.webhookSecret = webhookSecret;
  }

  public String getWorkerId() {
    return workerId;
  }

  public void setWorkerId(String workerId) {
    this.workerId = workerId;
  }

  public long getLockDurationMillis() {
    return lockDurationMillis;
  }

  public void setLockDurationMillis(long lockDurationMillis) {
    this.lockDurationMillis = lockDurationMillis;
  }

  public long getPollIntervalMillis() {
    return pollIntervalMillis;
  }

  public void setPollIntervalMillis(long pollIntervalMillis) {
    this.pollIntervalMillis = pollIntervalMillis;
  }

  public int getMaxTasksPerPoll() {
    return maxTasksPerPoll;
  }

  public void setMaxTasksPerPoll(int maxTasksPerPoll) {
    this.maxTasksPerPoll = maxTasksPerPoll;
  }

  public int getFailureRetries() {
    return failureRetries;
  }

  public void setFailureRetries(int failureRetries) {
    this.failureRetries = failureRetries;
  }

  public long getFailureRetryTimeoutMillis() {
    return failureRetryTimeoutMillis;
  }

  public void setFailureRetryTimeoutMillis(long failureRetryTimeoutMillis) {
    this.failureRetryTimeoutMillis = failureRetryTimeoutMillis;
  }

  public long getInvocationTimeoutMillis() {
    return invocationTimeoutMillis;
  }

  public void setInvocationTimeoutMillis(long invocationTimeoutMillis) {
    this.invocationTimeoutMillis = invocationTimeoutMillis;
  }

  public Set<String> getAllowedAgentHosts() {
    return allowedAgentHosts;
  }

  /** Comma-separated hostnames a task may point at; empty (the default) allows any host. */
  public void setAllowedAgentHosts(String commaSeparatedHosts) {
    this.allowedAgentHosts = commaSeparatedHosts == null ? Set.of()
        : Arrays.stream(commaSeparatedHosts.split(","))
            .map(h -> h.trim().toLowerCase(Locale.ROOT))
            .filter(h -> !h.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

}
