# Design: Webhook Integration for the External Agent Task

Status: proposed, not yet implemented.
Branch: `external_agent_integration` (cut from `origin/main`, i.e. the FINOS baseline — does not include `main`'s `engine-plugins/agentic-plugin`).
Scope: MVP webhook integration only, per `external_agent.md` (agent contract) and `complete_design.md` (buildout spec). No Kafka, no agent runtime, no new microservice.

---

## 1. Existing implementation (what we're extending)

There is no dedicated "Agent Task" activity type in the engine. What both spec docs call the Agent Task is:

- a plain `bpmn:serviceTask`
- with `camunda:type="external"` and `camunda:topic="<topic>"`
- i.e. an ordinary Camunda 7 **External Task**.

This is confirmed by the `resources/element-templates/agentic-task.json` element template in `fluxnova-modeler` (fork-only, `main`/`develop`): its only bindings are `camunda:type = external` (hidden) and `camunda:topic` (string). There is no `AgentTaskActivityBehavior` or custom `ActivityBehavior` anywhere in the engine.

A second, unrelated feature lives on this same shape: `engine-plugins/agentic-plugin` (fork-only, commit `a4be15734b`, not present on this branch) adds a `fluxnova:agentic` BPMN extension element (`maxAutonomySeconds`, `evidenceRequired`) plus an end-`ExecutionListener` that fails the activity unless an `agentEvidence` variable is set. **This is out of scope here** — neither `external_agent.md` nor `complete_design.md` references `evidenceRequired`/`agentEvidence`/`maxAutonomySeconds`. We build directly on the plain External Task and do not depend on, or need to port, `agentic-plugin`.

Today, the External Task's topic is picked up by a Node.js worker (`fluxnova-modeler/agent-task-worker`, fork-only): it fetches-and-locks, runs its own synchronous tool-calling loop, and completes/fails the task itself via the standard External Task API. That worker is being replaced, for this integration, by the flow in §5 — invocation and completion move to a webhook-driven, asynchronous flow instead of an in-process loop.

**Mechanism already reusable, and why it's the right one:** Camunda 7 External Tasks are fetch-and-lock — the engine does not hold a thread or a job for the duration of the lock. That's the existing, idiomatic answer to "keep the BPMN activity alive without blocking a thread while an external system runs for seconds, minutes, or days." `complete_design.md` §15's warnings (don't use a plain `JavaDelegate` and return, don't block a thread) are exactly the failure mode External Tasks were designed to avoid. So the smallest viable design keeps the activity as an External Task and adds only what's needed to (a) invoke the agent and (b) let a webhook — not the original fetching worker — complete or fail it later.

`org.finos.fluxnova.bpm.engine.externaltask.ExternalTask` already exposes everything needed for correlation, so no new ID scheme is required:

| Getter | Use |
|---|---|
| `getId()` | reused directly as WorkX's `executionId` — one external task = one logical Agent Execution, which is already a 1:1 relationship |
| `getProcessInstanceId()` | `processInstanceId` |
| `getActivityInstanceId()` | `activityInstanceId` (distinct from `getExecutionId()`, which is a process *execution* id, not an activity-instance id — confirmed by reading the interface, per `complete_design.md` §6.2's caution) |

---

## 2. Target architecture

```
BPMN Process
     |
     v
Agent Task  (bpmn:serviceTask, camunda:type=external, camunda:topic=expense-approval-agent)
     |
     v
[Invoker]  --- fetches & locks the external task, creates ADK session, calls /run, persists
     |          an AgentExecution row, returns without completing the task
     v
Durable Agent Execution Store  (new: agent-webhook-plugin's own table)
     ^
     |  lifecycle events (webhook, HMAC-signed)
     |
[Webhook resource]  POST /agent-webhook/events
     |
     v  (only on agent.completed / agent.failed)
ExternalTaskService.complete(...) / handleFailure(...)
     |
     v
BPMN Process continues
```

New module: `engine-plugins/agent-webhook-plugin`, sibling to `connect-plugin`/`identity-ldap`/`spin-plugin`. It owns:

1. The `AgentExecution` + processed-event persistence (§4).
2. The invoker that creates the ADK session and calls `/run` (§5).
3. The webhook JAX-RS resource (§6).

It does not touch `engine-rest`'s core `FluxnovaRestResources`/`DefaultApplication` (the fixed resource list used by the standalone WAR). Instead it registers its resource the way `starter-security` already does for admin-webapp plugin resources (`SsoLogoutAdminPluginRootResource` et al.) — contributed into the Spring Boot–embedded runtime independently of the core list. This repo's deployment surface is primarily `spring-boot-starter/*`, so this is the path of least resistance; it also means the plugin module has zero compile-time dependency on `engine-rest`.

---

## 3. Lifecycle event contract

DTO mirrors the envelope in `external_agent.md` §"Output: lifecycle events" / `complete_design.md` §8 verbatim — no reshaping, so the mapping stays a straight (de)serialization with no lossy translation:

```java
class LifecycleEventDto {
  String id;                 // dedup key
  Integer seq;                // per-run ordering
  String type;                 // agent.started | agent.waiting | agent.resumed | agent.completed | agent.failed
  String time;                // ISO-8601
  String source;
  String sessionId;
  String userId;
  String invocationId;
  CorrelationDto correlation;  // { processInstanceId, activityInstanceId, executionId }
  Map<String, Object> data;    // event-specific payload, kept opaque and stored as JSON
}
```

`data` is intentionally untyped at the DTO level — `agent.waiting`'s `{tool, args, result}` and `agent.completed`'s `{outcome, guardrail, response, tool_results}` shapes are read out only where needed (outcome mapping, wait-detail persistence) and the rest is stored as opaque JSON, per `complete_design.md` §12 ("do not store the agent's private memory / full internal reasoning" — we don't parse or re-expose fields we don't need).

---

## 4. Identifier, correlation, and persistence design

Two new tables, owned entirely by `agent-webhook-plugin` (see §7 for why this is a separate, lightweight store rather than a Camunda-core MyBatis entity):

**`FLUXNOVA_AGENT_EXECUTION`** (one row per External Task that is agent-backed)

| Column | Notes |
|---|---|
| `EXECUTION_ID` (PK) | = the External Task id |
| `PROCESS_INSTANCE_ID` | from `ExternalTask.getProcessInstanceId()` |
| `ACTIVITY_INSTANCE_ID` | from `ExternalTask.getActivityInstanceId()` |
| `EXTERNAL_TASK_ID` | same value as `EXECUTION_ID`; kept as its own column for readability at call sites (`ExternalTaskService.complete(externalTaskId, ...)`) |
| `AGENT_REF` | e.g. `expense-approval-agent` |
| `SESSION_ID` | the id WorkX picked for the ADK session |
| `STATE` | `CREATED, STARTING, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED` |
| `LAST_EVENT_ID`, `LAST_SEQ` | for dedup/gap detection |
| `BUSINESS_OUTCOME` | `approved / rejected / guardrail_blocked / null`, only set on `agent.completed` |
| `RESULT_JSON`, `ERROR_JSON`, `WAIT_JSON` | opaque payload snapshots for the current state |
| `CREATED_AT`, `UPDATED_AT` | |

**`FLUXNOVA_AGENT_EVENT`** — append-only, one row per accepted webhook delivery: `EVENT_ID` (unique, the dedup key), `EXECUTION_ID` (FK), `SEQ`, `TYPE`, `RECEIVED_AT`, `RAW_PAYLOAD`. Insert is the mechanism for idempotency: insert `EVENT_ID` under a unique constraint inside the same transaction as the `AgentExecution` state update; a duplicate delivery's insert fails the constraint, the transaction is a no-op, and the webhook still replies 2xx (the event was already processed — that's success, not an error, per the "at least once" contract).

Correlation on webhook receipt is by `correlation.executionId` (the primary key — a direct lookup), with `processInstanceId`/`activityInstanceId` checked as a consistency assertion, not as the lookup path. This matches `complete_design.md` §6.5 ("must use the correlation data to identify the exact Agent Execution ... without relying on session ID alone") while keeping the lookup O(1) on a primary key instead of a composite search.

State machine (validated transitions only, illegal transitions rejected and logged, not silently applied):

```
CREATED → STARTING → RUNNING ⇄ WAITING → {COMPLETED | FAILED}
                 \_______________________↗ (FAILED reachable from RUNNING or WAITING)
```

`seq` gap handling: if an incoming event's `seq` is not `lastSeq + 1` (and it isn't a duplicate of an already-seen id), the event is still persisted to `FLUXNOVA_AGENT_EVENT` (so nothing is lost) and the execution is flagged for reconciliation rather than silently advancing `STATE` — the terminal-event handler in particular refuses to complete/fail the BPMN activity if a gap is open, per `complete_design.md` §13 ("do not apply a later event while silently ignoring a missing earlier event"). Given the agent's own guarantee is "ordered per run," a gap in practice signals a genuinely lost delivery outside the 24h retry window; the MVP's reconciliation policy is to surface it (log + a queryable "needs attention" flag on the row) rather than to invent an active backfill call, since the agent side exposes no "replay since seq N" API in `external_agent.md`.

---

## 5. Invocation

Triggered by the plugin's own External Task fetch-and-lock loop against the configured topic (replacing the Node worker's role for agent-backed topics specifically — other topics on the same engine are untouched):

1. Fetch & lock (long lock duration — see timeout note below).
2. Insert `AgentExecution` row, `STATE=STARTING`.
3. `POST /apps/human_in_the_loop/users/{userId}/sessions/{externalTaskId}` with `lifecycle.correlation = {processInstanceId, activityInstanceId, executionId=externalTaskId}` and `callback_url` = this plugin's webhook URL.
4. `POST /run` with the process-variable-derived input text.
5. Update `STATE=RUNNING` (or leave to `agent.started` webhook to do it — see open question in §8). Return from the fetch handler without completing the task.

Per `complete_design.md` §7 ("do not treat the `/run` response body as the source of truth"), step 5's state update is provisional bookkeeping only; the authoritative state transitions all come from webhook events in §6.

**Lock duration doubles as the waiting-timeout mechanism**: set it to the configured SLA (e.g. the acceptance criteria's "waiting timeout" policy, default proposed 24h to match the agent's own redelivery window) rather than trying to run a separate timer job. If no terminal event arrives before the lock expires, the task becomes fetchable again; the invoker's fetch handler checks for an existing non-terminal `AgentExecution` row for that task id first and, if found, treats it as a timeout/escalation case (per `complete_design.md` §16) instead of blindly re-invoking the agent and creating a second session.

**Invocation-failure retries**: if step 3 or 4 throws, do not leave a dangling `STARTING` row — mark it `FAILED` with an `error` of stage=`invocation` and call `handleFailure` on the external task using the engine's normal retry/backoff (decrementing `retries`), so Camunda's existing retry mechanism is reused rather than a new one being built, per `complete_design.md` §16 ("apply a retry policy that avoids duplicate external sessions").

---

## 6. Webhook resource

`POST /agent-webhook/events`

1. Read raw body (needed before parsing, to verify the signature over the exact bytes).
2. If a secret is configured, verify `X-Lifecycle-Signature: sha256=<HMAC-SHA256(body, secret)>` using constant-time comparison; reject with 401 on mismatch, before touching persistence.
3. Parse into `LifecycleEventDto`.
4. Look up `AgentExecution` by `correlation.executionId`; 404 if unknown (do not guess/fall back to session id alone).
5. In one transaction: insert into `FLUXNOVA_AGENT_EVENT` (unique on `EVENT_ID` — duplicate short-circuits here), apply the validated state transition, update `LAST_EVENT_ID`/`LAST_SEQ`.
6. If the new state is `COMPLETED`: map `data.outcome` → process variable `agentBusinessOutcome` (`approved`/`rejected`/`guardrail_blocked`/`null`), `data.response` → `agentResponse`, then `ExternalTaskService.complete(executionId, variables)`.
   If `FAILED`: persist `data.stage`/`error_type`/`error_message`, then `ExternalTaskService.handleFailure(...)` following the task's configured retry policy.
7. Reply 2xx only after the transaction commits (durable-before-ack, per `complete_design.md` §13).

Terminal-event safety: step 6 only fires from the transition-application step in §5's state machine, which already refuses a second terminal transition on an execution that's already `COMPLETED`/`FAILED` — so a duplicate or delayed terminal event is absorbed at the state-machine level, not by a separate ad hoc check.

**Recovery after restart**: because the state transition and the `ExternalTaskService.complete/handleFailure` call are not in the same transaction as each other (the external task's own tables are in the engine's datasource, ours may or may not be the same datasource), a crash between "we recorded COMPLETED" and "we called `complete()`" is possible. Mitigation: a small recovery sweep on plugin startup (`ProcessEnginePlugin.postProcessEngineBuild`) that finds `AgentExecution` rows in a terminal `STATE` whose external task is still unresolved and re-drives step 6's call — idempotent because `ExternalTaskService.complete` on an already-completed/gone task is a no-op/expected exception we catch and log, not a retry loop.

---

## 7. Why a separate store instead of a Camunda-core entity

Camunda 7's own entities (`HistoricExternalTaskLog` etc.) are wired through core's MyBatis `mappings.xml` and `DbEntityManager` — extending that from an external plugin module means patching core registration points, which is more invasive than this MVP calls for and harder to keep isolated on this branch. None of the three existing plugins (`connect-plugin`, `identity-ldap`, `spin-plugin`) persist custom entities, so there's no in-repo precedent to follow either way. The proposed approach — the plugin owns its own two tables, created via a Liquibase changelog (there's already a Liquibase precedent in `spring-boot-starter/starter-qa/integration-test-liquibase`) run from the plugin's Spring Boot auto-configuration — keeps the new schema fully isolated, deployable independently of core engine schema upgrades, and removable without touching core DDL if the integration is ever discontinued.

---

## 8. Open questions / risks to confirm before coding

1. **`agent.started` vs. our own `STARTING→RUNNING` write**: §5 sets `RUNNING` optimistically after `/run` returns; the `agent.started` webhook will also try to apply `STARTING/RUNNING→RUNNING`. Proposed: treat `RUNNING` as idempotent (applying it twice is a no-op), so whichever arrives first wins and the other is a harmless no-op — avoids a race without needing a lock.
2. **Deployment target**: this design assumes Spring Boot embedded deployment (`spring-boot-starter/*`) for the webhook resource registration (§2, §7). If the standalone `engine-rest` WAR (`DefaultApplication`/`FluxnovaRestResources`) also needs to expose this endpoint, that fixed resource list does need a one-line addition — small, but touches `engine-rest` core, so flagging it now rather than discovering it mid-implementation.
3. **Outbound auth to the ADK server**: `external_agent.md` doesn't specify an auth scheme for `/apps/.../sessions` or `/run` beyond the callback allow-list; the MVP assumes none is required beyond network-level trust, configurable later if the deployed agent adds one.
4. **`userId` mapping**: `external_agent.md`'s `/run` call requires a `user_id`; `complete_design.md` doesn't specify how WorkX derives it. Proposed default: a configurable static value for the MVP (e.g. a service-account id), not derived per-process-instance, since no process-variable convention for "the requesting user" was specified.

---

## 9. Implementation plan

1. New module `engine-plugins/agent-webhook-plugin` (pom, `ProcessEnginePlugin`).
2. `AgentExecution`/`AgentEvent` persistence + Liquibase changelog.
3. State machine + transition validation (pure logic, unit-testable without Camunda).
4. Invoker: fetch-and-lock loop + ADK client (session create + `/run`).
5. Webhook resource: signature verification, parsing, correlation, transactional apply, terminal-event completion/failure.
6. Startup recovery sweep.
7. Configuration surface (base URL, app name, secret, timeouts, retry policy) via standard Spring Boot `@ConfigurationProperties`.
8. Tests: state-machine unit tests; plugin integration tests (mocked ADK + simulated webhook deliveries) covering the 20 scenarios in `complete_design.md` §18, including duplicate/out-of-order delivery, invalid signature, and restart recovery.
9. `README.md` in the new module documenting the webhook contract, configuration, and execution flow (deliverable §4 in the MVP instructions).

Nothing in this plan touches `engine-rest` core, the modeler, or requires `agentic-plugin`.
