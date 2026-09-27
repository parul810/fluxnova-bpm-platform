package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model;

import java.time.Instant;

/**
 * Durable record of one logical Agent Execution: one row per agent-backed
 * External Task. The row's {@code executionId} is the External Task's own id
 * (see design doc section 1) - there is no separate WorkX id scheme.
 */
public class AgentExecution {

  private String executionId;
  private String processInstanceId;
  private String activityId;
  private String activityInstanceId;
  private String externalTaskId;
  private String agentRef;
  private String sessionId;
  private AgentExecutionState state;
  private String lastEventId;
  private Integer lastSeq;
  private String businessOutcome;
  private String resultJson;
  private String errorJson;
  private String waitJson;
  private boolean sequenceGap;
  private Instant createdAt;
  private Instant updatedAt;

  public String getExecutionId() {
    return executionId;
  }

  public void setExecutionId(String executionId) {
    this.executionId = executionId;
  }

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public void setProcessInstanceId(String processInstanceId) {
    this.processInstanceId = processInstanceId;
  }

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

  public String getExternalTaskId() {
    return externalTaskId;
  }

  public void setExternalTaskId(String externalTaskId) {
    this.externalTaskId = externalTaskId;
  }

  public String getAgentRef() {
    return agentRef;
  }

  public void setAgentRef(String agentRef) {
    this.agentRef = agentRef;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public AgentExecutionState getState() {
    return state;
  }

  public void setState(AgentExecutionState state) {
    this.state = state;
  }

  public String getLastEventId() {
    return lastEventId;
  }

  public void setLastEventId(String lastEventId) {
    this.lastEventId = lastEventId;
  }

  public Integer getLastSeq() {
    return lastSeq;
  }

  public void setLastSeq(Integer lastSeq) {
    this.lastSeq = lastSeq;
  }

  public String getBusinessOutcome() {
    return businessOutcome;
  }

  public void setBusinessOutcome(String businessOutcome) {
    this.businessOutcome = businessOutcome;
  }

  public String getResultJson() {
    return resultJson;
  }

  public void setResultJson(String resultJson) {
    this.resultJson = resultJson;
  }

  public String getErrorJson() {
    return errorJson;
  }

  public void setErrorJson(String errorJson) {
    this.errorJson = errorJson;
  }

  public String getWaitJson() {
    return waitJson;
  }

  public void setWaitJson(String waitJson) {
    this.waitJson = waitJson;
  }

  public boolean isSequenceGap() {
    return sequenceGap;
  }

  public void setSequenceGap(boolean sequenceGap) {
    this.sequenceGap = sequenceGap;
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
