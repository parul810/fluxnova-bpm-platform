
# Buildout Specification: External Agent Lifecycle Integration in Fluxnova

## 1. Objective

Extend Fluxnova's existing **Agent Task** to consume lifecycle events from an external agent runtime and manage the Agent Task's execution lifecycle within the BPMN process.

The initial integration target is the **Expense Approval Agent**, which runs on an ADK server and supports human-in-the-loop approval.

The goal is to enable a single BPMN Agent Task to:

1. Invoke an external agent.
2. Track its execution through lifecycle events.
3. Remain active while the agent runs or waits for human interaction.
4. Resume tracking when the agent continues.
5. Complete only when the agent emits a terminal event.
6. Map the final result to BPMN process variables and route the process accordingly.

**Repository:** https://github.com/parul810/fluxnova-bpm-platform/tree/external_agent_integration

Start by inspecting the existing public repository and its Agent Task implementation. Reuse existing Fluxnova and Camunda 7 capabilities wherever possible.

Do not begin by creating a parallel Agent Task implementation.

---

## 2. Core Architectural Principle

**WorkX orchestrates the business process. The external agent runtime orchestrates the agent execution.**

The Agent Task is a process-level abstraction, not an agent-framework abstraction.

The external agent may perform multiple internal reasoning loops, invoke tools, maintain memory, pause for human approval, and resume later.

WorkX should not need to understand these internal details.

From the BPMN perspective:

**One Agent Task execution = one logical Agent Execution.**

The Agent Task should remain independent of:

* The number of internal reasoning turns.
* The agent's internal tool calls.
* The agent's memory and conversation implementation.
* The agent's internal orchestration framework.
* The agent's reasoning strategy.

WorkX should only manage the external execution's lifecycle, correlation, process integration, and final result.

---

## 3. Scope

### In scope

* Extend the existing Fluxnova Agent Task.
* Invoke an external agent through its supported API.
* Consume lifecycle events through a webhook-based integration.
* Maintain a durable Agent Execution record.
* Correlate lifecycle events with the correct BPMN activity.
* Handle running, waiting, resumed, completed, and failed states.
* Support durable suspension and resumption of the BPMN activity.
* Map final business outcomes to BPMN process variables.
* Handle duplicate, delayed, and out-of-order events.
* Implement timeout, failure, and recovery handling.
* Add automated tests for the complete lifecycle.

### Out of scope

* Agent Sub-process and agentic subprocess functionality.
* Building agents inside WorkX.
* Implementing the external agent's internal reasoning or tool loop.
* Managing external agent memory.
* Building a new agent registry.
* Replacing Camunda 7.
* Introducing a new microservice unless the existing architecture requires it.
* Building a general-purpose human approval system. Reuse existing Fluxnova/Camunda human-task capabilities where appropriate.

---

## 4. First Step: Inspect the Existing Implementation

Before modifying code, examine the repository and identify:

1. The existing Agent Task BPMN representation and modeler configuration.
2. The runtime implementation responsible for executing the Agent Task.
3. The current agent invocation mechanism.
4. How the Agent Task currently handles execution completion and failure.
5. Existing Camunda 7 asynchronous execution, job handling, and persistence mechanisms.
6. How process variables are mapped into the agent input and how outputs are mapped back.
7. Existing event, callback, webhook, or messaging infrastructure.
8. Existing tests and extension points.

Trace the complete execution path:

```text
BPMN Agent Task
      |
      v
Agent Task Runtime
      |
      v
Agent Invocation
      |
      v
Execution Completion
      |
      v
BPMN Continues
```

Identify the smallest set of changes needed to extend this flow to support asynchronous external-agent lifecycle events.

**Deliverable:** A concise summary of the current implementation, reusable components, required modifications, and architectural risks.

Do not modify code until the proposed design has been documented.

---

## 5. Target Architecture

Reuse the existing Agent Task and introduce only the integration components required to support external execution.

```text
WORKX / FLUXNOVA
┌──────────────────────────────────────────────┐
│                                              │
│  BPMN Process                                │
│       │                                      │
│       ▼                                      │
│  Agent Task                                  │
│       │                                      │
│       ▼                                      │
│  Agent Adapter / Integration Layer           │
│       │                                      │
│       ├── Create external session            │
│       ├── Invoke external agent              │
│       ├── Correlate lifecycle events         │
│       ├── Persist execution state            │
│       ├── Suspend / resume BPMN              │
│       └── Map final result                   │
│                                              │
│  Durable Agent Execution Store               │
│                                              │
└──────────────────────┬───────────────────────┘
                       │
                       │ External Agent Contract
                       ▼
┌──────────────────────────────────────────────┐
│ EXTERNAL AGENT RUNTIME                       │
│                                              │
│  Agent Execution                             │
│       ├── Internal reasoning loops           │
│       ├── Tools                              │
│       ├── Memory / session                   │
│       ├── Human interaction                  │
│       └── Final structured result            │
│                                              │
│  Lifecycle Events → Webhook                  │
│                                              │
└──────────────────────────────────────────────┘
```

The Agent Adapter is a logical integration boundary. It does not have to be a separate microservice.

Prefer a lightweight component within the existing application unless the current architecture justifies otherwise.

---

## 6. Identifier and Correlation Model

Maintain four conceptually distinct identifiers.

| Identifier             | Owner                                           | Purpose                                                      |
| ---------------------- | ----------------------------------------------- | ------------------------------------------------------------ |
| `processInstanceId`  | Camunda 7                                       | Identifies the BPMN process instance                         |
| `activityInstanceId` | Camunda 7                                       | Identifies the specific Agent Task activity execution        |
| `executionId`        | WorkX                                           | Identifies one logical external Agent Execution              |
| `sessionId`          | External agent session; value selected by WorkX | Identifies the external agent's session and stateful context |

### 6.1 Process Instance ID

Use Camunda's process instance ID.

It identifies the overall business process execution.

A single process instance may invoke multiple Agent Tasks or invoke the same Agent Task more than once.

### 6.2 Activity Instance ID

Obtain the activity-instance identifier from the existing Camunda execution context or supported runtime APIs.

It identifies the specific BPMN activity execution associated with the external agent invocation.

Verify the exact identifier available at the Agent Task's execution point. Do not assume that every Camunda execution ID is equivalent to an activity-instance ID.

### 6.3 WorkX Execution ID

Generate a unique, durable `executionId` when creating the Agent Execution record.

It identifies one logical external agent invocation and its lifecycle record.

Use it to correlate lifecycle events, persist state, and recover execution after restart.

### 6.4 External Session ID

The external ADK server requires a session ID.

WorkX selects the session ID and passes it to the external agent's session-creation API.

The session ID identifies the agent's context and may be used by the external runtime to retain state across waiting and resumption.

The current Expense Approval Agent specification recommends using the process instance ID as the session ID to make session-creation retries idempotent.

Validate whether this convention is safe when the same process instance invokes the same agent multiple times.

If necessary, use a stable session ID derived from the specific Agent Execution instead.

Do not use a random session ID on every retry.

### 6.5 Correlation Contract

Pass the following WorkX identifiers through the agent's lifecycle correlation object:

```json
{
  "processInstanceId": "PI-100",
  "activityInstanceId": "AI-200",
  "executionId": "EX-300"
}
```

The external agent must copy the correlation object unchanged into every lifecycle event.

Keep the external `session_id` as a separate field.

WorkX must use the correlation data to identify the exact Agent Execution and BPMN activity without relying on session ID alone.

---

## 7. External Agent Invocation Contract

The Expense Approval Agent runs on an ADK server.

Default endpoint:

`http://localhost:8000`

Application name:

`human_in_the_loop`

The server requires two calls.

### Step 1: Create the session

```http
POST /apps/human_in_the_loop/users/{userId}/sessions/{sessionId}
Content-Type: application/json
```

Example request:

```json
{
  "lifecycle": {
    "correlation": {
      "processInstanceId": "PI-100",
      "activityInstanceId": "AI-200",
      "executionId": "EX-300"
    },
    "callback_url": "https://example.com/agent-events",
    "kafka_topic": "expense-agent.events",
    "outcome_from": "request_approval.status"
  }
}
```

Use only the lifecycle sinks and configuration supported by the deployed agent runtime.

For the initial POC, prefer the webhook sink.

### Step 2: Invoke the agent

```http
POST /run
Content-Type: application/json
```

Example request:

```json
{
  "app_name": "human_in_the_loop",
  "user_id": "USER-123",
  "session_id": "PI-100",
  "new_message": {
    "role": "user",
    "parts": [
      {
        "text": "Request approval for an expense of $250 for a team lunch."
      }
    ]
  }
}
```

The input must contain both the expense amount and reason.

### Invocation behavior

The `/run` call returns when the agent pauses or finishes.

**Do not treat the `/run` response body as the source of truth for task completion.**

Lifecycle events are the authoritative contract for execution state and final outcome.

Handle invocation failures separately from asynchronous lifecycle failures.

---

## 8. Lifecycle Event Contract

The external agent emits lifecycle events using a common envelope.

Example:

```json
{
  "id": "evt-001",
  "seq": 1,
  "type": "agent.started",
  "time": "2026-09-27T10:15:02Z",
  "source": "human_in_the_loop",
  "session_id": "PI-100",
  "user_id": "USER-123",
  "invocation_id": "INV-001",
  "correlation": {
    "processInstanceId": "PI-100",
    "activityInstanceId": "AI-200",
    "executionId": "EX-300"
  },
  "data": {}
}
```

The consumer must handle the following events.

| Event               | Meaning                                   | WorkX behavior                                  |
| ------------------- | ----------------------------------------- | ----------------------------------------------- |
| `agent.started`   | Agent execution started                   | Mark execution as running                       |
| `agent.waiting`   | Agent paused for human approval           | Persist wait details and suspend the Agent Task |
| `agent.resumed`   | Human decision received and agent resumed | Mark execution as running                       |
| `agent.completed` | Agent execution finished successfully     | Persist result, map output, complete Agent Task |
| `agent.failed`    | Agent execution failed                    | Persist error and apply failure policy          |

Only `agent.completed` and `agent.failed` are terminal events.

The agent may emit multiple nonterminal events during one logical execution.

Do not equate an intermediate event with execution completion.

---

## 9. Agent Execution State Model

Maintain a normalized execution state model.

| State         | Meaning                                               |
| ------------- | ----------------------------------------------------- |
| `CREATED`   | Execution record created                              |
| `STARTING`  | Session creation or invocation in progress            |
| `RUNNING`   | External agent is executing                           |
| `WAITING`   | External agent is paused and waiting                  |
| `COMPLETED` | External agent completed successfully                 |
| `FAILED`    | External agent execution failed                       |
| `CANCELLED` | Execution was cancelled, if cancellation is supported |

Treat `CREATED`, `STARTING`, `RUNNING`, and `WAITING` as nonterminal states.

Treat `COMPLETED`, `FAILED`, and `CANCELLED` as terminal states.

Implement explicit, validated state transitions.

A valid typical sequence is:

```text
CREATED
   |
   v
STARTING
   |
   v
RUNNING
   |
   v
WAITING
   |
   v
RUNNING
   |
   v
COMPLETED
```

The execution may also transition from `RUNNING` or `WAITING` to `FAILED`.

Do not mark the Agent Task complete when it enters `WAITING`.

Do not hold a Java thread open while waiting for a human response.

---

## 10. Human Interaction and Waiting

When the agent emits `agent.waiting`, the event contains details such as:

```json
{
  "tool": "request_approval",
  "args": {
    "amount": 250,
    "reason": "team lunch"
  },
  "result": {
    "status": "pending",
    "approval_request_id": "APR-001"
  }
}
```

WorkX should:

1. Persist the waiting state and approval request details.
2. Suspend the Agent Task while preserving the BPMN process state.
3. Optionally coordinate a WorkX human task or SLA timer if required.
4. Continue tracking the same external execution.
5. Allow the external approval service to resume the agent.
6. Process `agent.resumed` and continue tracking.
7. Complete only after a terminal event.

The external agent owns the approval request and internal session.

WorkX owns the business-process lifecycle and any human-workflow coordination that it explicitly provides.

Do not build an independent approval engine for the POC.

---

## 11. Final Result and Business Outcome Mapping

The `agent.completed` event contains the final result.

Example:

```json
{
  "outcome": "approved",
  "guardrail": null,
  "response": "The expense has been approved.",
  "tool_results": []
}
```

The business outcome is available in `data.outcome`.

Map outcomes as follows:

| Outcome               | Business meaning                           | Suggested BPMN handling |
| --------------------- | ------------------------------------------ | ----------------------- |
| `approved`          | Human approved the expense                 | Approved path           |
| `rejected`          | Human rejected the expense                 | Rejected path           |
| `guardrail_blocked` | Policy or safety guardrail stopped the run | Policy/review path      |
| `null`              | Execution finished without a decision      | Needs-attention path    |

Do not equate technical completion with business approval.

Maintain separate fields for:

* Execution state.
* Business outcome.
* Final response.
* Guardrail information.
* Tool results, if needed.
* Technical error details.

Map the final structured result to BPMN process variables using the existing Fluxnova output-mapping mechanism.

Avoid exposing internal agent reasoning or unnecessary tool details to the BPMN process.

---

## 12. Durable Agent Execution Record

Create or extend a persistence model for the logical Agent Execution.

At minimum, the record should contain:

```json
{
  "executionId": "EX-300",
  "processInstanceId": "PI-100",
  "activityInstanceId": "AI-200",
  "agentRef": "expense-approval-agent",
  "agentVersion": "1.0",
  "sessionId": "PI-100",
  "externalInvocationId": "INV-001",
  "state": "WAITING",
  "lastProcessedEventId": "evt-003",
  "lastProcessedSequence": 3,
  "businessOutcome": null,
  "wait": {
    "type": "HUMAN_APPROVAL",
    "interactionId": "APR-001"
  },
  "result": null,
  "error": null,
  "createdAt": "2026-09-27T10:15:00Z",
  "updatedAt": "2026-09-27T10:16:00Z"
}
```

This is a conceptual schema. Adapt it to the existing persistence model and naming conventions.

The record must support:

* Correlation between process, activity, execution, and session.
* Lifecycle state transitions.
* Event deduplication.
* Recovery after restart.
* Final output persistence.
* Waiting-state tracking.
* Failure and timeout handling.

Do not store the agent's private memory or full internal reasoning in this record.

---

## 13. Event Consumption and Reliability

The Expense Approval Agent guarantees:

* At-least-once event delivery.
* Ordered events per run.
* Unique event IDs.
* Sequence numbers per run.
* Event persistence across agent restarts.
* Retried failed deliveries with increasing delays for up to 24 hours.

The consumer must not assume exactly-once delivery.

### Required behavior

#### Deduplication

Use the event `id` to identify duplicate events.

Persist processed event IDs and ensure that duplicate delivery does not cause duplicate BPMN completion or duplicate state transitions.

#### Ordering

Use `seq` to order events and detect gaps.

Do not apply a later event while silently ignoring a missing earlier event.

For example:

```text
Received: seq 1, seq 3

Expected: seq 1, seq 2, seq 3
```

Define a reconciliation strategy for missing events.

The agent's stated delivery guarantees should be validated against the deployed integration.

#### Durable processing

Persist the event and its state transition safely before acknowledging the webhook.

Avoid a failure mode in which WorkX acknowledges an event but loses the corresponding state update.

#### Terminal events

Ensure that only one terminal transition is accepted for a given logical execution.

Handle late or duplicate terminal events safely.

#### Waiting timeout

A waiting execution can remain paused for minutes or days.

Implement a configurable timeout or SLA policy.

If the approval service fails to resume the agent, the execution must not remain indefinitely invisible.

Timeout handling should be distinct from the agent's own terminal lifecycle event.

---

## 14. Webhook Integration

For the initial POC, use the webhook sink.

The external agent sends an HTTP POST containing the lifecycle event JSON.

Relevant headers include:

* `X-Lifecycle-Event-Id`
* `X-Lifecycle-Event-Type`
* `X-Lifecycle-Signature`

If a webhook secret is configured, verify the HMAC signature:

`sha256=<HMAC of request body>`

Reject invalid signatures.

The webhook endpoint should:

1. Validate the request and signature.
2. Parse and validate the lifecycle event.
3. Resolve the Agent Execution using correlation identifiers.
4. Persist the event durably.
5. Ensure safe processing and acknowledgement.
6. Trigger or schedule the relevant state transition.

Keep the event-consumer interface transport-independent so that Kafka can be added later.

Do not assume that the Kafka sink is production-ready; the current specification states that it has not been tested against a real broker.

---

## 15. Camunda 7 Execution and Suspension

Inspect the existing Fluxnova Agent Task runtime and determine the safest way to keep the BPMN process waiting while the external agent executes.

The implementation must:

* Avoid blocking a Java thread for the duration of the agent execution.
* Avoid completing the BPMN activity on the `/run` response.
* Persist sufficient state to recover after application restart.
* Resume the correct activity when a terminal event is processed.
* Prevent duplicate completion if an event is delivered more than once.

Evaluate whether the existing Agent Task can be extended using the current Camunda 7 asynchronous execution and job mechanisms.

If a custom activity behavior or another execution pattern is required, explain why and keep it as small as possible.

Do not assume that an ordinary Java Delegate can simply return after launching an asynchronous request and leave the activity safely suspended.

---

## 16. Retry, Timeout, and Failure Policies

Distinguish the following failure categories:

### Invocation failure

The external session creation or `/run` call fails before a usable execution is established.

Apply a retry policy that avoids duplicate external sessions or runs.

### Agent execution failure

The agent emits `agent.failed`.

Persist the error details, including `stage`, `error_type`, and `error_message`.

Apply the configured failure policy.

### Delivery failure

The external agent cannot deliver a lifecycle event to WorkX.

Use the webhook's retry behavior and ensure the consumer is idempotent.

### Missing event or sequence gap

Detect missing events and reconcile the execution state.

Do not mark the Agent Task complete based on assumptions.

### Waiting timeout

If the execution remains in `WAITING` beyond the configured threshold, trigger the appropriate timeout or escalation policy.

Do not automatically treat a timeout as an agent failure unless the configured policy explicitly requires it.

### Retry safety

Do not create a new external Agent Execution for every retry of the same invocation.

Use stable identifiers and idempotent session creation.

Do not automatically restart a failed business operation if doing so could create duplicate approval requests or other side effects.

---

## 17. Configuration and Security

Use the existing Fluxnova configuration and secret-management mechanisms.

Support configuration for:

* External agent base URL.
* Agent application name.
* User identity mapping.
* Webhook callback URL.
* Webhook secret.
* Invocation timeout.
* Waiting timeout or SLA policy.
* Retry policy.

Do not hardcode credentials, secrets, or production endpoints.

The external agent's callback URL must be configured and allow-listed on the agent side.

Validate incoming webhook signatures and prevent unauthorized callbacks.

Use appropriate authentication for outbound calls to the agent server.

Avoid logging sensitive expense data or unnecessary agent context.

---

## 18. Testing Requirements

Add automated tests covering the full Agent Task lifecycle.

### Functional tests

1. Successful invocation and completion.
2. `agent.started` followed by `agent.completed`.
3. `agent.started` → `agent.waiting` → `agent.resumed` → `agent.completed`.
4. Approved outcome mapping.
5. Rejected outcome mapping.
6. Guardrail-blocked outcome mapping.
7. Null outcome and needs-attention routing.
8. `agent.failed` handling.
9. Correct final-result mapping to BPMN variables.

### Reliability tests

10. Duplicate lifecycle event delivery.
11. Out-of-order events.
12. Missing sequence number and reconciliation.
13. Invalid webhook signature.
14. Webhook acknowledgement and durable event processing.
15. Application restart while the agent is waiting.
16. Recovery after a terminal event is persisted but before BPMN completion.
17. Waiting timeout and escalation.
18. Invocation retry without duplicate execution.
19. Multiple Agent Task invocations within one process instance.
20. Correct correlation of events to the appropriate activity execution.

### Camunda integration tests

Verify that:

* The process remains active while the agent is running.
* The process remains suspended while the agent is waiting.
* The correct activity resumes after lifecycle events arrive.
* The process advances only after successful terminal-state processing.
* Failure and cancellation behavior are consistent with the configured process policy.

Use mocks or a test agent server for automated tests. Include an end-to-end test against the actual Expense Approval Agent where the environment permits.

---

## 19. Implementation Sequence

Proceed incrementally.

### Phase 1: Discovery

Inspect the existing Agent Task implementation.

Produce:

* Current architecture and execution flow.
* Existing extension points.
* Components to reuse.
* Required code changes.
* Camunda 7 suspension and resumption strategy.
* Persistence and event-consumption proposal.

### Phase 2: Contract and Design

Define:

* External lifecycle event DTOs.
* Agent Execution state model.
* Correlation and identifier strategy.
* Durable execution record.
* Event deduplication and ordering strategy.
* Webhook processing flow.
* Final-result and business-outcome mapping.

Provide a concise architecture diagram and explain any assumptions.

### Phase 3: Minimal Implementation

Implement the smallest viable changes to the existing Agent Task.

Prioritize:

1. Durable execution record.
2. Session creation and external invocation.
3. Webhook lifecycle event consumption.
4. Correlation and deduplication.
5. Waiting and resumption.
6. Terminal result mapping.
7. Failure and timeout handling.

Do not introduce unrelated refactoring or new infrastructure without justification.

### Phase 4: Validation

Run the automated tests and validate the complete approval lifecycle.

Document any remaining limitations.

---

## 20. Expected Deliverables

### Before implementation

Provide:

1. Existing Agent Task implementation summary.
2. Proposed architecture and lifecycle diagram.
3. Recommended lifecycle event contract.
4. Identifier and correlation strategy.
5. Persistence design.
6. Camunda 7 suspension and resumption design.
7. Files and components to modify.
8. Implementation plan and identified risks.

### After implementation

Provide:

1. Summary of the implemented changes.
2. List of modified and added files.
3. Explanation of the execution lifecycle.
4. Configuration required to run the integration.
5. Test results.
6. Known limitations and follow-up items.

---

## 21. Acceptance Criteria

The implementation is complete when:

* The existing Agent Task can invoke the external Expense Approval Agent.
* One Agent Task execution maps to one logical Agent Execution.
* The four identifiers are handled consistently: `processInstanceId`, `activityInstanceId`, `executionId`, and `sessionId`.
* Lifecycle events are correlated to the correct Agent Execution.
* Duplicate events do not cause duplicate process transitions.
* The BPMN process remains active while the agent executes.
* The Agent Task can remain waiting for human approval without holding a Java thread.
* The same external execution can resume after the human decision.
* The Agent Task completes only on a terminal event.
* Business outcomes are mapped independently of technical execution status.
* The final result is persisted and mapped to BPMN process variables.
* Failures, timeouts, and recovery are handled safely.
* Automated tests validate the complete lifecycle.

---

## Final Design Principle

**The Agent Task is a single BPMN activity that tracks one external Agent Execution from invocation to terminal completion.**

WorkX owns the business-process lifecycle, correlation, persistence, and process integration.

The external agent owns its internal reasoning, memory, tools, and human-interaction logic.

Extend the existing Fluxnova Agent Task to support this boundary with the smallest reliable implementation possible.
