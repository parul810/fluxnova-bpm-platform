package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.statemachine;

import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.dto.LifecycleEventDto;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutionStateMachineTest {

  private final AgentExecutionStateMachine stateMachine = new AgentExecutionStateMachine();

  @ParameterizedTest
  @CsvSource({
      "STARTING, agent.started, RUNNING",
      "RUNNING, agent.waiting, WAITING",
      "WAITING, agent.resumed, RUNNING",
      "RUNNING, agent.resumed, RUNNING",
      "RUNNING, agent.completed, COMPLETED",
      "WAITING, agent.completed, COMPLETED",
      "STARTING, agent.failed, FAILED",
      "WAITING, agent.failed, FAILED",
  })
  void allowsValidTransitions(AgentExecutionState from, String eventType, AgentExecutionState expected) {
    assertThat(stateMachine.nextState(from, eventType)).isEqualTo(expected);
  }

  @Test
  void treatsRepeatedStartedAsIdempotentNoOp() {
    // The invoker's own optimistic RUNNING write and a genuine agent.started webhook can race;
    // applying agent.started again from RUNNING must not be rejected as illegal.
    assertThat(stateMachine.nextState(RUNNING, LifecycleEventDto.TYPE_STARTED)).isEqualTo(RUNNING);
  }

  @Test
  void rejectsWaitingBeforeRunning() {
    IllegalAgentStateTransitionException e = assertThrows(IllegalAgentStateTransitionException.class,
        () -> stateMachine.nextState(CREATED, LifecycleEventDto.TYPE_WAITING));
    assertThat(e.isDuplicateTerminal()).isFalse();
  }

  @Test
  void rejectsUnknownEventType() {
    assertThrows(IllegalAgentStateTransitionException.class,
        () -> stateMachine.nextState(RUNNING, "agent.unknown"));
  }

  @ParameterizedTest
  @CsvSource({
      "COMPLETED, agent.completed",
      "COMPLETED, agent.failed",
      "FAILED, agent.completed",
      "FAILED, agent.failed",
      "CANCELLED, agent.started",
  })
  void flagsLateEventOnTerminalExecutionAsDuplicateNotError(AgentExecutionState terminal, String eventType) {
    IllegalAgentStateTransitionException e = assertThrows(IllegalAgentStateTransitionException.class,
        () -> stateMachine.nextState(terminal, eventType));
    assertThat(e.isDuplicateTerminal()).isTrue();
  }

}
