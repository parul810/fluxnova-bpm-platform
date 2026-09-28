# Design: Webhook Integration for the External Agent Task

Status: implemented on this branch. This began as the design; where the build differs from it, the section says **As built**.
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
Agent Task  (bpmn:serviceTask, camunda:type=external, camunda:topic=fluxnova-agent;
     |            agent URL / name / user / instruction set on the task itself, see §5)
     |
     v
[Invoker]  --- fetches & locks the external task, resolves the agent from the task, creates the session, calls /run, persists
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
3. The webhook JAX-RS resource (§6), which also serves a read-only status endpoint for monitoring.

It does not touch `engine-rest`'s core `FluxnovaRestResources`/`DefaultApplication` (the fixed resource list used by the standalone WAR).

**As built:** the resource is plain JAX-RS (`jakarta.ws.rs`), so the plugin has no compile-time dependency on `engine-rest` or Spring. In a Spring Boot deployment it is mounted by overriding `FluxnovaJerseyResourceConfig#registerAdditionalResources()`; the starter only creates its own `FluxnovaJerseyResourceConfig` bean `@ConditionalOnMissingBean`, so a user-supplied one takes over. An earlier draft proposed the `starter-security` admin-plugin mechanism (`SsoLogoutAdminPluginRootResource` et al.). That is the webapp plugin SPI for interactive users and is the wrong shape for a machine-to-machine webhook, so it was dropped.

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
| `ACTIVITY_ID` | the BPMN element id, from `ExternalTask.getActivityId()` (added by a later changeset); lets a monitoring view key state by diagram element |
| `ACTIVITY_INSTANCE_ID` | from `ExternalTask.getActivityInstanceId()` |
| `EXTERNAL_TASK_ID` | same value as `EXECUTION_ID`; kept as its own column for readability at call sites (`ExternalTaskService.complete(externalTaskId, ...)`) |
| `AGENT_REF` | **As built:** the external task's topic. With one shared topic this no longer says which agent ran; the per-task endpoint is not persisted (see §8) |
| `SESSION_ID` | the id WorkX picked for the ADK session |
| `STATE_` | `CREATED, STARTING, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED` |
| `LAST_EVENT_ID`, `LAST_SEQ` | for dedup/gap detection |
| `BUSINESS_OUTCOME` | `approved / rejected / guardrail_blocked / null`, only set on `agent.completed` |
| `RESULT_JSON`, `ERROR_JSON`, `WAIT_JSON` | opaque payload snapshots for the current state |
| `SEQUENCE_GAP` | sticky flag set when an event arrives with a `seq` gap |
| `CREATED_AT`, `UPDATED_AT` | |

An event whose `seq` is *lower* than the last applied one is **stale**: it is recorded for audit but must not move the state backwards. A replay of the *same* `seq` is not stale; it falls through to the event-id dedup and the state machine, which is what makes a redelivered terminal event a safe no-op.

**`FLUXNOVA_AGENT_EVENT`** — append-only, one row per accepted webhook delivery: `EVENT_ID` (unique, the dedup key), `EXECUTION_ID` (FK), `SEQ`, `TYPE_`, `RECEIVED_AT`, `RAW_PAYLOAD`. Insert is the mechanism for idempotency: insert `EVENT_ID` under a unique constraint inside the same transaction as the `AgentExecution` state update; a duplicate delivery's insert fails the constraint, the transaction is a no-op, and the webhook still replies 2xx (the event was already processed — that's success, not an error, per the "at least once" contract).

Correlation on webhook receipt is by `correlation.executionId` (the primary key — a direct lookup), with `processInstanceId`/`activityInstanceId` checked as a consistency assertion, not as the lookup path. This matches `complete_design.md` §6.5 ("must use the correlation data to identify the exact Agent Execution ... without relying on session ID alone") while keeping the lookup O(1) on a primary key instead of a composite search.

State machine (validated transitions only, illegal transitions rejected and logged, not silently applied):

```
CREATED → STARTING → RUNNING ⇄ WAITING → {COMPLETED | FAILED}
                 \_______________________↗ (FAILED reachable from RUNNING or WAITING)
```

`seq` gap handling: if an incoming event's `seq` is not `lastSeq + 1` (and it isn't a duplicate of an already-seen id), the event is still persisted to `FLUXNOVA_AGENT_EVENT` (so nothing is lost) and the row gets a sticky `SEQUENCE_GAP` flag (the state itself still advances) — the terminal-event handler in particular refuses to complete/fail the BPMN activity if a gap is open, per `complete_design.md` §13 ("do not apply a later event while silently ignoring a missing earlier event"). Given the agent's own guarantee is "ordered per run," a gap in practice signals a genuinely lost delivery outside the 24h retry window; the MVP's reconciliation policy is to surface it (a warning log and the `SEQUENCE_GAP` flag on the row; the flag is not exposed by the status endpoint yet) rather than to invent an active backfill call, since the agent side exposes no "replay since seq N" API in `external_agent.md`.

---

## 5. Invocation

Triggered by the plugin's own External Task fetch-and-lock loop against the one shared topic (`fluxnova-agent` by default). Other topics on the same engine are untouched.

1. Fetch & lock (long lock duration — see timeout note below), asking the engine to include the task's **extension properties**.
2. Insert an `AgentExecution` row, `STATE_=STARTING`.
3. Resolve the agent for *this task* (next subsection), then `POST /apps/{appName}/users/{userId}/sessions/{externalTaskId}` with `lifecycle.correlation = {processInstanceId, activityInstanceId, executionId=externalTaskId}` and `callback_url` = this plugin's webhook URL.
4. `POST /run` with the instruction text.
5. Move `STARTING → RUNNING` and return without completing the task.

**As built, step 5 is a compare-and-set, not an overwrite.** `/run` returns only after the agent has paused or finished, and by then its own `agent.started`/`agent.waiting` webhooks may already have landed. The first version wrote `RUNNING` unconditionally and clobbered `WAITING`; live testing caught it (the status showed `RUNNING` next to a populated waiting reason). Now only a row still in `STARTING` becomes `RUNNING`; whatever the webhooks already set stands.

Per `complete_design.md` §7 ("do not treat the `/run` response body as the source of truth"), step 5 is provisional bookkeeping only; the authoritative state transitions all come from webhook events in §6.

### Per-task agent configuration (as built)

The agent is chosen by the task, the way a service task carries its own URL, so adding an agent needs no engine config or restart. Values come from the task's extension properties and fall back to the plugin's defaults:

| On the task | Where | Falls back to |
|---|---|---|
| `agent.baseUrl` | extension property | `adkBaseUrl` |
| `agent.appName` | extension property | `appName` |
| `agent.userId` | extension property | `userId` |
| `agent.protocol` | extension property (`adk` is the only client built in) | `adk` |
| `agentInputText` | input parameter (an expression such as `${instruction}` lets the text be supplied at start) | `amount` + `reason` variables (expense-specific) |

`AgentEndpointResolver` builds an `AgentEndpoint` from these; `agent.protocol` selects an `AgentClient` implementation (`AdkAgentClient` today). Because a model author now chooses the URL, the resolver validates it (http/https with a host) and, when `allowedAgentHosts` is set, restricts it to those hosts. Path segments taken from the model are URL-encoded. Any bad setting fails that task with a message naming the host and cause and raises an incident, rather than doing nothing. The modeler side is the `External Agent Task` element template (`fluxnova-modeler/resources/element-templates/external-agent-task.json`), which binds these fields with `camunda:property` and `camunda:inputParameter`.

**Lock duration doubles as the waiting-timeout mechanism**: set it to the configured SLA (default 24h, matching the agent's own redelivery window) rather than running a separate timer job. If no terminal event arrives before the lock expires, the task becomes fetchable again; the invoker checks for an existing non-terminal `AgentExecution` for that task id first and, if found, treats it as a timeout (extends the lock, logs a warning) instead of re-invoking the agent and creating a second session, per `complete_design.md` §16.

**Invocation-failure retries**: if resolving or calling the agent throws, the row is marked `FAILED` with an error of stage=`invocation` and `handleFailure` is called on the external task using the engine's normal retry/backoff, so Camunda's own retry mechanism is reused. **As built:** if an operator later resets the task's retries, the invoker recognises the old `FAILED` row, deletes it (and its events) and starts a clean execution. Without this the retried task was silently ignored as "already terminal" until its lock expired.

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

**As built**, a successful delivery answers `{"status": "<outcome>"}` where the outcome is one of `ACCEPTED`, `DUPLICATE` (event id already seen), `ALREADY_TERMINAL` (a late or repeated event on a finished execution), `STALE_EVENT_RECORDED` (older `seq`; recorded, state untouched) or `TERMINAL_BLOCKED_BY_GAP` (a terminal event arrived with an open sequence gap, so the task was deliberately *not* completed). All of these are 2xx because the delivery guarantee is at-least-once. Rejections are `401` (bad signature), `400` (malformed or missing `id`/`type`/`correlation.executionId`), `404` (unknown execution), `409` (invalid transition) and `503` (plugin not initialised).

`GET /agent-webhook/executions?processInstanceId=…` is a read-only status endpoint. It returns each agent execution's `activityId`, state, business outcome, and — while waiting — the tool and arguments it is waiting on. The Cockpit overlay (`webapps/.../diagramPlugins/agentExecutionStatus.js`) polls it to badge each agent task on the process-instance diagram.

Terminal-event safety: step 6 only fires from the transition-application step in §4's state machine, which already refuses a second terminal transition on an execution that's already `COMPLETED`/`FAILED` — so a duplicate or delayed terminal event is absorbed at the state-machine level, not by a separate ad hoc check.

**Recovery after restart — not yet built.** Because the state transition and the `ExternalTaskService.complete/handleFailure` call are not in the same transaction as each other (the external task's own tables are in the engine's datasource, ours may or may not be the same datasource), a crash between "we recorded COMPLETED" and "we called `complete()`" is possible. Planned mitigation: a small recovery sweep on plugin startup (`ProcessEnginePlugin.postProcessEngineBuild`) that finds `AgentExecution` rows in a terminal `STATE` whose external task is still unresolved and re-drives step 6's call — idempotent because `ExternalTaskService.complete` on an already-completed/gone task is a no-op/expected exception we catch and log, not a retry loop.

---

## 7. Why a separate store instead of a Camunda-core entity

Camunda 7's own entities (`HistoricExternalTaskLog` etc.) are wired through core's MyBatis `mappings.xml` and `DbEntityManager` — extending that from an external plugin module means patching core registration points, which is more invasive than this MVP calls for and harder to keep isolated on this branch. None of the three existing plugins (`connect-plugin`, `identity-ldap`, `spin-plugin`) persist custom entities, so there's no in-repo precedent to follow either way. The approach — the plugin owns its own two tables, created by a Liquibase changelog that `SchemaInitializer` runs when the plugin starts (there's already a Liquibase precedent in `spring-boot-starter/starter-qa/integration-test-liquibase`), so it works in any deployment, not only Spring Boot — keeps the new schema fully isolated, deployable independently of core engine schema upgrades, and removable without touching core DDL if the integration is ever discontinued.

---

## 8. Open questions and risks

Resolved during the build:

1. **`agent.started` vs. the invoker's own `STARTING→RUNNING` write** — resolved with a compare-and-set, see §5. The original "whichever arrives first wins" was not what the first implementation did.
2. **Deployment target** — resolved for Spring Boot via `registerAdditionalResources()` (§2). Still open if the standalone `engine-rest` WAR must expose the endpoint: that needs a one-line addition to `FluxnovaRestResources`, which touches `engine-rest` core. Not done.
4. **`userId` mapping** — now `agent.userId` on the task, with a plugin-level default.

Still open:

3. **Outbound auth to the agent server.** `external_agent.md` specifies none beyond the callback allow-list; the plugin assumes network-level trust.
5. **Secrets are engine-wide.** One webhook signing secret covers every agent. Per-agent secrets or auth tokens are not supported, and belong outside the BPMN file (e.g. a named secret the engine resolves).
6. **`AGENT_REF` records the shared topic**, so an execution row no longer says which agent endpoint it called. Persisting the resolved base URL and agent name would fix this.
7. **The Cockpit overlay hardcodes the `/engine-rest/...` path.** It works where the REST API is mounted there and fails silently where it isn't.
8. **Restart recovery** (§6) and a **waiting-timeout policy** beyond "the lock expired, extend it and log" are not built.

---

## 9. What was built

Everything in §§2–7 above, in `engine-plugins/agent-webhook-plugin`, plus:

- The Cockpit status overlay, `webapps/frontend/ui/monitoring/plugins/base/app/views/processInstance/diagramPlugins/agentExecutionStatus.js`.
- The `External Agent Task` element template, in the separate `fluxnova-modeler` repository.

Configuration is a plain setter bean (`AgentWebhookProperties`, set through `AgentWebhookProcessEnginePlugin`), not Spring `@ConfigurationProperties`, so the plugin stays framework-agnostic. See the module README for the property table and the wiring snippet.

Testing: 49 unit tests (state machine, signature verification, webhook orchestration against an in-memory repository, the JDBC repository and Liquibase changelog on H2, endpoint resolution, and the invoker against a mocked engine). The full lifecycle was also run live against a real ADK agent: started → waiting → resumed → completed, the approved/rejected/`null`-outcome routing through a gateway, the invocation-failure incident, and per-task endpoint selection. There is no automated test that boots a real process engine; that gap is why the live runs mattered.

Nothing here touches `engine-rest` core or requires `agentic-plugin`.
