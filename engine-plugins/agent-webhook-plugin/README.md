# agent-webhook-plugin

Webhook lifecycle integration for the External Agent Task. Extends the
existing Fluxnova Agent Task (a plain `bpmn:serviceTask` with
`camunda:type="external"` + `camunda:topic`) so that an external agent
runtime (initially the Expense Approval Agent) can drive the task's
execution asynchronously via lifecycle webhooks, instead of a worker
completing it synchronously in one fetch-and-lock cycle.

See `/WEBHOOK_INTEGRATION_DESIGN.md` at the repo root for the full design
rationale. This README covers only what's needed to configure and run it.

## What it does

1. Fetches-and-locks external tasks on a configured topic.
2. Creates a session with the external agent and calls `/run`, then leaves
   the task locked - it does **not** complete the task on that response.
3. Exposes `POST /agent-webhook/events` to receive the agent's lifecycle
   events (`agent.started`, `agent.waiting`, `agent.resumed`,
   `agent.completed`, `agent.failed`).
4. Persists a durable execution record per task, deduplicates events by id,
   detects sequence gaps, and only completes/fails the external task on a
   terminal event (`agent.completed` / `agent.failed`).

## Configuration

Set these on the `AgentWebhookProcessEnginePlugin` bean (via `processes.xml`
plugin properties, or directly on the Spring-managed bean):

| Property | Default | Meaning |
|---|---|---|
| `topicName` | `fluxnova-agent` | The one shared topic every agent task uses |
| `adkBaseUrl` | `http://localhost:8000` | Default agent URL, used when a task doesn't set its own |
| `appName` | `human_in_the_loop` | Default agent name, used when a task doesn't set its own |
| `userId` | `workx-service-account` | Default `user_id`, used when a task doesn't set its own |
| `webhookCallbackUrl` | *(none)* | Callback URL registered with the agent at session creation |
| `webhookSecret` | *(none)* | HMAC secret for `X-Lifecycle-Signature`; verification is skipped if unset |
| `workerId` | `agent-webhook-plugin` | External task worker id used for fetch/complete/fail |
| `lockDurationMillis` | `86400000` (24h) | External task lock duration - doubles as the waiting-timeout |
| `pollIntervalMillis` | `2000` | Fetch-and-lock poll interval |
| `maxTasksPerPoll` | `10` | Max tasks fetched per poll |
| `failureRetries` / `failureRetryTimeoutMillis` | `0` / `0` | Retry policy passed to `handleFailure` on `agent.failed` or invocation failure |
| `invocationTimeoutMillis` | `30000` | HTTP timeout for the session-create and `/run` calls |
| `allowedAgentHosts` | *(empty = any)* | Comma-separated hostnames a task may point at. Set this in production: model authors choose the URL |

## Configuring an agent from the modeler

Each agent task carries its own settings, the way a service task carries its
own URL. No engine config or restart is needed to add an agent.

Use the **External Agent Task** element template
(`fluxnova-modeler/resources/element-templates/external-agent-task.json`),
or set these by hand on a service task with `camunda:type="external"` and
`camunda:topic="fluxnova-agent"`:

| On the task | Where | Falls back to |
|---|---|---|
| `agent.baseUrl` | extension property | `adkBaseUrl` |
| `agent.appName` | extension property | `appName` |
| `agent.userId` | extension property | `userId` |
| `agent.protocol` | extension property (`adk` is the only one built in) | `adk` |
| `agentInputText` | input parameter: what to tell the agent | `amount` + `reason` variables (expense-specific) |

```xml
<bpmn:serviceTask id="AgentTask_1" name="Run Expense Agent"
    camunda:type="external" camunda:topic="fluxnova-agent">
  <bpmn:extensionElements>
    <camunda:properties>
      <camunda:property name="agent.baseUrl" value="http://localhost:8000" />
      <camunda:property name="agent.appName" value="human_in_the_loop" />
    </camunda:properties>
    <camunda:inputOutput>
      <camunda:inputParameter name="agentInputText">Create an expense report for this customer</camunda:inputParameter>
    </camunda:inputOutput>
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

A bad setting (unreachable URL, non-http URL, host outside `allowedAgentHosts`,
unknown protocol) fails that task with a message naming the problem and raises
an incident, rather than doing nothing.

Adding an agent that speaks a different API means one new `AgentClient`
implementation, registered under a new `agent.protocol` value in
`AgentWebhookProcessEnginePlugin`. The agent must still report progress with the
lifecycle events described above.

Still engine-level, not per task: the webhook callback URL, the signing secret
(keep secrets out of BPMN files), and the polling settings.

## Mounting the webhook endpoint

`AgentWebhookResource` is a plain JAX-RS resource (`jakarta.ws.rs`), kept
framework-agnostic on purpose. To actually serve it in a Spring
Boot deployment, register it on the existing extension point rather than
touching `engine-rest` core:

```java
@Configuration
public class AgentWebhookRestConfig {

  @Bean
  public FluxnovaJerseyResourceConfig fluxnovaJerseyResourceConfig() {
    return new FluxnovaJerseyResourceConfig() {
      @Override
      protected void registerAdditionalResources() {
        register(AgentWebhookResource.class);
      }
    };
  }
}
```

This works because `FluxnovaBpmRestJerseyAutoConfiguration` only creates its
default `FluxnovaJerseyResourceConfig` bean `@ConditionalOnMissingBean` - a
user-supplied one (as above) takes over, and the plugin adds nothing to the
standalone `engine-rest` WAR's resource list.

## Underlying database

No new infrastructure - the plugin's two tables
(`FLUXNOVA_AGENT_EXECUTION`, `FLUXNOVA_AGENT_EVENT`) are created via
Liquibase (`src/main/resources/db/changelog/db.changelog-master.xml`)
against the *same* datasource the process engine already uses. The engine
supports h2/PostgreSQL/MySQL/MSSQL/Oracle/DB2; the changelog uses Liquibase's
vendor-neutral changeset syntax so it works on all of them. Tests run
against H2; point the engine's own datasource at a real vendor (e.g. a local
Postgres) to validate the changelog there before going to production.

## Known limitations

- A crash between committing a terminal state transition and the subsequent
  `ExternalTaskService.complete`/`handleFailure` call is logged but not yet
  actively reconciled by a startup sweep. The state machine's
  duplicate-terminal guard prevents a second automatic completion once
  noticed; manual reconciliation is needed for that narrow window today.
- The waiting-timeout policy is the external task's lock duration expiring;
  there's no separate configurable SLA/escalation policy yet beyond
  extending the lock and logging.
- Kafka delivery, agent sub-processes, and a general approval system are
  explicitly out of scope for this MVP (see the design doc).
