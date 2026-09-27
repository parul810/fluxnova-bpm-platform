package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.invoker;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.AgentWebhookProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * Client for the two calls in {@code external_agent.md} / design doc section
 * 7: session creation and {@code /run}. Both are short synchronous HTTP
 * calls; the agent's own asynchronous progress after that point is tracked
 * exclusively through webhook lifecycle events, never through these
 * responses (design doc section 7, "do not treat the /run response body as
 * the source of truth").
 */
public class AdkAgentClient implements AgentClient {

  private final AgentWebhookProperties properties;
  private final HttpClient httpClient;
  private final JsonMapper objectMapper = JsonMapper.builder().build();

  public AdkAgentClient(AgentWebhookProperties properties) {
    this.properties = properties;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.getInvocationTimeoutMillis()))
        .build();
  }

  /** {@code POST /apps/{appName}/users/{userId}/sessions/{sessionId}} with the lifecycle correlation object. */
  @Override
  public void createSession(AgentEndpoint endpoint, String sessionId, String processInstanceId,
      String activityInstanceId, String executionId) {
    Map<String, Object> correlation = new LinkedHashMap<>();
    correlation.put("processInstanceId", processInstanceId);
    correlation.put("activityInstanceId", activityInstanceId);
    correlation.put("executionId", executionId);

    Map<String, Object> lifecycle = new LinkedHashMap<>();
    lifecycle.put("correlation", correlation);
    if (properties.getWebhookCallbackUrl() != null) {
      lifecycle.put("callback_url", properties.getWebhookCallbackUrl());
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("lifecycle", lifecycle);

    String path = String.format("/apps/%s/users/%s/sessions/%s", segment(endpoint.appName()),
        segment(endpoint.userId()), segment(sessionId));
    post(endpoint, path, body);
  }

  /** {@code POST /run}. Returns once the agent pauses or finishes - a few seconds, per the agent's contract. */
  @Override
  public void run(AgentEndpoint endpoint, String sessionId, String inputText) {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("text", inputText);

    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "user");
    message.put("parts", java.util.List.of(part));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("app_name", endpoint.appName());
    body.put("user_id", endpoint.userId());
    body.put("session_id", sessionId);
    body.put("new_message", message);

    post(endpoint, "/run", body);
  }

  /** Path segments now come from the model, so they are encoded rather than trusted. */
  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private void post(AgentEndpoint endpoint, String path, Map<String, Object> body) {
    String json = objectMapper.writeValueAsString(body);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(endpoint.baseUrl() + path))
        .timeout(Duration.ofMillis(properties.getInvocationTimeoutMillis()))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
        .build();
    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new AgentInvocationException(
            "Agent at " + endpoint.baseUrl() + " rejected " + path + " with status " + response.statusCode() + ": "
                + response.body());
      }
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new AgentInvocationException("Could not reach agent at " + endpoint.baseUrl() + " (" + path + "): "
          + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()), e);
    }
  }

}
