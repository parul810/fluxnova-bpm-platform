package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.persistence;

import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecution;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the plugin's real Liquibase changelog against H2, validating both the
 * changelog itself and the repository's dedup/transition semantics - see
 * design doc section 4 and the "underlying DB" discussion (H2 for automated
 * tests, the deployment's real vendor for production).
 */
class JdbcAgentExecutionRepositoryTest {

  private JdbcAgentExecutionRepository repository;

  @BeforeEach
  void setUp() {
    DataSource dataSource = newInMemoryDataSource();
    new SchemaInitializer().run(dataSource);
    repository = new JdbcAgentExecutionRepository(dataSource);
  }

  @Test
  void insertsAndFindsByExecutionId() {
    AgentExecution execution = newExecution("exec-1");
    repository.insert(execution);

    Optional<AgentExecution> found = repository.findByExecutionId("exec-1");

    assertThat(found.isPresent()).isTrue();
    assertThat(found.get().getProcessInstanceId()).isEqualTo("pi-1");
    assertThat(found.get().getActivityInstanceId()).isEqualTo("ai-1");
    assertThat(found.get().getState()).isEqualTo(AgentExecutionState.STARTING);
    assertThat(found.get().isSequenceGap()).isFalse();
  }

  @Test
  void returnsEmptyForUnknownExecution() {
    assertThat(repository.findByExecutionId("does-not-exist").isPresent()).isFalse();
  }

  @Test
  void applyTransitionPersistsStateAndEvent() {
    repository.insert(newExecution("exec-2"));
    AgentExecution updated = newExecution("exec-2");
    updated.setState(AgentExecutionState.RUNNING);
    updated.setLastEventId("evt-1");
    updated.setLastSeq(1);

    boolean applied = repository.applyTransitionIfNewEvent(updated, "evt-1", 1, "agent.started", "{\"raw\":true}");

    assertThat(applied).isTrue();
    AgentExecution reloaded = repository.findByExecutionId("exec-2").orElseThrow();
    assertThat(reloaded.getState()).isEqualTo(AgentExecutionState.RUNNING);
    assertThat(reloaded.getLastEventId()).isEqualTo("evt-1");
    assertThat(reloaded.getLastSeq()).isEqualTo(1);
  }

  @Test
  void duplicateEventIdIsRejectedAndLeavesStateUnchanged() {
    repository.insert(newExecution("exec-3"));
    AgentExecution firstUpdate = newExecution("exec-3");
    firstUpdate.setState(AgentExecutionState.RUNNING);
    firstUpdate.setLastEventId("evt-1");
    firstUpdate.setLastSeq(1);
    repository.applyTransitionIfNewEvent(firstUpdate, "evt-1", 1, "agent.started", "{}");

    // Same event id delivered again (at-least-once retry), but claiming a different target state -
    // must be rejected as a duplicate and must NOT move the execution to COMPLETED.
    AgentExecution replay = newExecution("exec-3");
    replay.setState(AgentExecutionState.COMPLETED);
    replay.setLastEventId("evt-1");
    replay.setLastSeq(1);
    boolean appliedAgain = repository.applyTransitionIfNewEvent(replay, "evt-1", 1, "agent.started", "{}");

    assertThat(appliedAgain).isFalse();
    assertThat(repository.findByExecutionId("exec-3").orElseThrow().getState()).isEqualTo(AgentExecutionState.RUNNING);
  }

  @Test
  void updateStateChangesStateWithoutTouchingEventLog() {
    repository.insert(newExecution("exec-4"));
    AgentExecution execution = repository.findByExecutionId("exec-4").orElseThrow();
    execution.setState(AgentExecutionState.FAILED);
    execution.setErrorJson("{\"stage\":\"invocation\"}");

    repository.updateState(execution);

    AgentExecution reloaded = repository.findByExecutionId("exec-4").orElseThrow();
    assertThat(reloaded.getState()).isEqualTo(AgentExecutionState.FAILED);
    assertThat(reloaded.getErrorJson()).isEqualTo("{\"stage\":\"invocation\"}");
  }

  @Test
  void markRunningIfStartingMovesStartingToRunning() {
    repository.insert(newExecution("exec-5"));

    assertThat(repository.markRunningIfStarting("exec-5")).isTrue();
    assertThat(repository.findByExecutionId("exec-5").orElseThrow().getState()).isEqualTo(AgentExecutionState.RUNNING);
  }

  @Test
  void markRunningIfStartingDoesNotOverwriteAStateTheWebhooksAlreadySet() {
    repository.insert(newExecution("exec-6"));
    AgentExecution waiting = newExecution("exec-6");
    waiting.setState(AgentExecutionState.WAITING);
    waiting.setLastEventId("evt-1");
    waiting.setLastSeq(2);
    repository.applyTransitionIfNewEvent(waiting, "evt-1", 2, "agent.waiting", "{}");

    assertThat(repository.markRunningIfStarting("exec-6")).isFalse();
    assertThat(repository.findByExecutionId("exec-6").orElseThrow().getState()).isEqualTo(AgentExecutionState.WAITING);
  }

  private AgentExecution newExecution(String executionId) {
    AgentExecution execution = new AgentExecution();
    execution.setExecutionId(executionId);
    execution.setExternalTaskId(executionId);
    execution.setProcessInstanceId("pi-1");
    execution.setActivityInstanceId("ai-1");
    execution.setAgentRef("expense-approval-agent");
    execution.setSessionId(executionId);
    execution.setState(AgentExecutionState.STARTING);
    return execution;
  }

  private DataSource newInMemoryDataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    String dbName = "agentwebhook_" + UUID.randomUUID().toString().replace("-", "");
    dataSource.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("sa");
    return dataSource;
  }

}
