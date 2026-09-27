
Expense Approval Agent: integration summary
What it does
The agent takes an expense request in plain language, e.g. "Request approval for $250 for a team lunch", and gets it approved or rejected by a human.

It extracts the amount and reason. If either is missing, it asks for it and ends the run.
It submits an approval request to the approval service, then pauses. Nothing runs while it waits.
A person approves or rejects it in any UI; the demo uses a Streamlit app. The approval service then resumes the agent.
The agent replies with the result and finishes.
It could wait for the human for minutes or days. Throughout, it publishes lifecycle events to a webhook and/or Kafka. The agent doesn't know who consumes them.

Input: how to start a run
The agent runs on an ADK server (default http://localhost:8000, app name human_in_the_loop). Starting a run takes two calls.

1. Create a session. The request body is the session's initial state. Use your process instance id as the session id, so a retried call can't start a duplicate run:

POST /apps/human_in_the_loop/users/{userId}/sessions/{processInstanceId}
{
  "lifecycle": {
    "correlation":  { "processInstanceId": "pi-111" },     // copied back unchanged on every event
    "callback_url": "https://…/agent-events",              // optional, must be on the allow-list
    "kafka_topic":  "expense-agent.events",                // optional, must be on the allow-list
    "outcome_from": "request_approval.status"              // optional, if not set on the server
  }
}
2. Send the request:

POST /run
{ "app_name": "human_in_the_loop", "user_id": "{userId}", "session_id": "{processInstanceId}",
  "new_message": { "role": "user", "parts": [{ "text": "Request approval for an expense of $250 for a team lunch." }] } }
/run returns once the agent pauses or finishes, which takes a few seconds. It does not wait for the human. Ignore its response body; the lifecycle events are the contract.
Always include both the amount and the reason in the text. Otherwise the agent asks a question and the run ends without a decision (outcome: null).
Output: lifecycle events
Every event has the same envelope:

{
  "id": "evt_…",            // unique; use it to drop duplicates
  "seq": 3,                 // 1, 2, 3… per run; use it to order events and spot gaps
  "type": "agent.resumed",
  "time": "2026-09-27T10:15:02+00:00",
  "source": "human_in_the_loop",
  "session_id": "pi-111",
  "user_id": "…",
  "invocation_id": "…",
  "correlation": { "processInstanceId": "pi-111" },
  "data": { … }
}
Event	Meaning	Key data
agent.started	Run began	input
agent.waiting	Approval submitted; agent paused waiting for a human	tool: "request_approval", args: {amount, reason}, result: {status: "pending", approval_request_id, …}
agent.resumed	Human decided; agent continues	outcome (approved/rejected), result
agent.completed	Final. Run finished	outcome, guardrail, response (agent's final text), tool_results
agent.failed	Final. Technical failure	stage (model/tool/run), error_type, error_message
Every run ends with exactly one of agent.completed or agent.failed.

Branching on agent.completed → data.outcome
outcome	Meaning	Suggested handling
"approved"	Human approved	Approved path
"rejected"	Human rejected	Rejected path
"guardrail_blocked"	A safety/policy guardrail stopped the run; data.guardrail gives source, reason, message	Policy/review path; retrying won't help
null	Finished without a decision (e.g. it asked for missing info)	Treat as "needs attention"
agent.failed means something broke (e.g. the approval service was down). Treat it as an incident, which may be retried.

Typical sequences

Approved / rejected:  started → waiting → (human decides) → resumed → completed(approved | rejected)
Missing info:         started → completed(null)
Guardrail:            started → completed(guardrail_blocked)
Failure:              started → failed          (can also happen after waiting)
Delivery
Webhook	Kafka
Format	POST of the event JSON	Key session_id; value is the event JSON
Metadata	X-Lifecycle-Event-Id, X-Lifecycle-Event-Type, X-Lifecycle-Signature: sha256=<HMAC of body></hmac> if a secret is set	CloudEvents headers: ce_id, ce_type, ce_source, ce_time, ce_subject
Success	Any 2xx	Broker acknowledgement
Guarantees: at least once, in order per run, and events survive agent restarts. Failed deliveries are retried with growing delays for up to 24h.

The consumer must:

Drop duplicates by id. The same event can arrive twice.
Use seq to put events back in order and notice gaps.
Set a timeout on agent.waiting. If the approval service can't resume the agent, no further event comes.
Verify X-Lifecycle-Signature if a webhook secret is configured.
Mapping to a single BPMN box (one suggestion)
Event	Fluxnova action
agent.started	Set a status variable (optional)
agent.waiting	Status variable; optionally a non-interrupting message for an SLA timer
agent.resumed	Status variable; optionally stop the SLA timer
agent.completed	Complete the box with outcome, then a gateway on outcome
agent.failed	Failure/incident on the box
Deployment settings needed on the agent side

LIFECYCLE_SINKS=webhook,kafka
LIFECYCLE_WEBHOOK_URL=…            LIFECYCLE_WEBHOOK_SECRET=…
LIFECYCLE_KAFKA_BOOTSTRAP_SERVERS=…  LIFECYCLE_KAFKA_TOPIC=…
LIFECYCLE_OUTCOME_FROM=request_approval.status
adk api_server --extra_plugins agent_lifecycle.LifecyclePlugin
Known limits
The Kafka sink hasn't been tested against a real broker yet. The webhook sink is tested end to end.
If the agent declines in its own words ("I can't do that"), that shows as outcome: null, not guardrail_blocked.
/run returns HTTP 500 when the provider blocks the prompt. The lifecycle event is still completed / guardrail_blocked.
