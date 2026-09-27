package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.webhook;

import java.time.Instant;

/**
 * Read-only view of one {@code AgentExecution}, shaped for a diagram overlay:
 * keyed by {@code activityId} (the BPMN element id) rather than an internal
 * execution id, with the waiting reason already unpacked from {@code waitJson}
 * into plain fields.
 */
public class AgentExecutionStatusDto {

  private String activityId;
  private String activityInstanceId;
  private String state;
  private String businessOutcome;
  private String waitingTool;
  private String waitingArgs;
  private String errorMessage;
  private Instant createdAt;
  private Instant updatedAt;

  public String getActivityId() {
    return activityId;
  }

  public void setActivityId(String activityId) {
    this.activityId = activityId;
  }

  public String getActivityInstanceId() {
    return activityInstanceId;
  }

  public void setActivityInstanceId(String activityInstanceId) {
    this.activityInstanceId = activityInstanceId;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public String getBusinessOutcome() {
    return businessOutcome;
  }

  public void setBusinessOutcome(String businessOutcome) {
    this.businessOutcome = businessOutcome;
  }

  public String getWaitingTool() {
    return waitingTool;
  }

  public void setWaitingTool(String waitingTool) {
    this.waitingTool = waitingTool;
  }

  public String getWaitingArgs() {
    return waitingArgs;
  }

  public void setWaitingArgs(String waitingArgs) {
    this.waitingArgs = waitingArgs;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

}
