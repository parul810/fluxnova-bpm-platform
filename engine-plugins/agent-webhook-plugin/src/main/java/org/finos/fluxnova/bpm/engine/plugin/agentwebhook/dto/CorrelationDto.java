package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The {@code correlation} object every lifecycle event carries unchanged from
 * session creation, per {@code external_agent.md} and design doc section 6.5.
 */
public class CorrelationDto {

  @JsonProperty("processInstanceId")
  private String processInstanceId;

  @JsonProperty("activityInstanceId")
  private String activityInstanceId;

  @JsonProperty("executionId")
  private String executionId;

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public void setProcessInstanceId(String processInstanceId) {
    this.processInstanceId = processInstanceId;
  }

  public String getActivityInstanceId() {
    return activityInstanceId;
  }

  public void setActivityInstanceId(String activityInstanceId) {
    this.activityInstanceId = activityInstanceId;
  }

  public String getExecutionId() {
    return executionId;
  }

  public void setExecutionId(String executionId) {
    this.executionId = executionId;
  }

}
