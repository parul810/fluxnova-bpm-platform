package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/**
 * Lifecycle event envelope, mirroring {@code external_agent.md} /
 * {@code complete_design.md} section 8 verbatim. {@code data} is kept as an
 * opaque {@link JsonNode} - only the fields actually needed (outcome, wait
 * details, error details) are read out of it; the rest is stored as-is and
 * never re-interpreted, per the "don't store the agent's private reasoning"
 * guidance in the design doc.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LifecycleEventDto {

  public static final String TYPE_STARTED = "agent.started";
  public static final String TYPE_WAITING = "agent.waiting";
  public static final String TYPE_RESUMED = "agent.resumed";
  public static final String TYPE_COMPLETED = "agent.completed";
  public static final String TYPE_FAILED = "agent.failed";

  @JsonProperty("id")
  private String id;

  @JsonProperty("seq")
  private Integer seq;

  @JsonProperty("type")
  private String type;

  @JsonProperty("time")
  private String time;

  @JsonProperty("source")
  private String source;

  @JsonProperty("session_id")
  private String sessionId;

  @JsonProperty("user_id")
  private String userId;

  @JsonProperty("invocation_id")
  private String invocationId;

  @JsonProperty("correlation")
  private CorrelationDto correlation;

  @JsonProperty("data")
  private JsonNode data;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public Integer getSeq() {
    return seq;
  }

  public void setSeq(Integer seq) {
    this.seq = seq;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getTime() {
    return time;
  }

  public void setTime(String time) {
    this.time = time;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getInvocationId() {
    return invocationId;
  }

  public void setInvocationId(String invocationId) {
    this.invocationId = invocationId;
  }

  public CorrelationDto getCorrelation() {
    return correlation;
  }

  public void setCorrelation(CorrelationDto correlation) {
    this.correlation = correlation;
  }

  public JsonNode getData() {
    return data;
  }

  public void setData(JsonNode data) {
    this.data = data;
  }

}
