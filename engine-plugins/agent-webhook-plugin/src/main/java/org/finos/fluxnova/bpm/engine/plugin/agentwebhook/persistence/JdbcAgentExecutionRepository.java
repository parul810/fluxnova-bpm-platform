package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import javax.sql.DataSource;

import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecution;
import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.model.AgentExecutionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain-JDBC implementation against the two tables created by
 * {@code db/changelog/db.changelog-master.xml}. Deliberately not a Camunda
 * MyBatis mapper - see design doc section 7.
 */
public class JdbcAgentExecutionRepository implements AgentExecutionRepository {

  private static final Logger LOG = LoggerFactory.getLogger(JdbcAgentExecutionRepository.class);

  /** SQLSTATE class "23" = Integrity Constraint Violation; portable across H2/Postgres/MySQL/Oracle/MSSQL/DB2. */
  private static final String SQLSTATE_INTEGRITY_VIOLATION_CLASS = "23";

  private final DataSource dataSource;

  public JdbcAgentExecutionRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void insert(AgentExecution execution) {
    String sql = "INSERT INTO FLUXNOVA_AGENT_EXECUTION "
        + "(EXECUTION_ID, PROCESS_INSTANCE_ID, ACTIVITY_ID, ACTIVITY_INSTANCE_ID, EXTERNAL_TASK_ID, AGENT_REF, "
        + "SESSION_ID, STATE_, LAST_EVENT_ID, LAST_SEQ, BUSINESS_OUTCOME, RESULT_JSON, ERROR_JSON, WAIT_JSON, "
        + "SEQUENCE_GAP, CREATED_AT, UPDATED_AT) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    Instant now = Instant.now();
    execution.setCreatedAt(now);
    execution.setUpdatedAt(now);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, execution.getExecutionId());
      ps.setString(2, execution.getProcessInstanceId());
      ps.setString(3, execution.getActivityId());
      ps.setString(4, execution.getActivityInstanceId());
      ps.setString(5, execution.getExternalTaskId());
      ps.setString(6, execution.getAgentRef());
      ps.setString(7, execution.getSessionId());
      ps.setString(8, execution.getState().name());
      ps.setString(9, execution.getLastEventId());
      setNullableInt(ps, 10, execution.getLastSeq());
      ps.setString(11, execution.getBusinessOutcome());
      ps.setString(12, execution.getResultJson());
      ps.setString(13, execution.getErrorJson());
      ps.setString(14, execution.getWaitJson());
      ps.setBoolean(15, execution.isSequenceGap());
      ps.setTimestamp(16, Timestamp.from(now));
      ps.setTimestamp(17, Timestamp.from(now));
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new AgentPersistenceException("Failed to insert AgentExecution " + execution.getExecutionId(), e);
    }
  }

  private static final String SELECT_COLUMNS = "EXECUTION_ID, PROCESS_INSTANCE_ID, ACTIVITY_ID, "
      + "ACTIVITY_INSTANCE_ID, EXTERNAL_TASK_ID, AGENT_REF, SESSION_ID, STATE_, LAST_EVENT_ID, LAST_SEQ, "
      + "BUSINESS_OUTCOME, RESULT_JSON, ERROR_JSON, WAIT_JSON, SEQUENCE_GAP, CREATED_AT, UPDATED_AT";

  @Override
  public Optional<AgentExecution> findByExecutionId(String executionId) {
    String sql = "SELECT " + SELECT_COLUMNS + " FROM FLUXNOVA_AGENT_EXECUTION WHERE EXECUTION_ID = ?";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, executionId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(mapRow(rs));
      }
    } catch (SQLException e) {
      throw new AgentPersistenceException("Failed to load AgentExecution " + executionId, e);
    }
  }

  @Override
  public java.util.List<AgentExecution> findByProcessInstanceId(String processInstanceId) {
    String sql = "SELECT " + SELECT_COLUMNS + " FROM FLUXNOVA_AGENT_EXECUTION WHERE PROCESS_INSTANCE_ID = ?";
    java.util.List<AgentExecution> executions = new java.util.ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, processInstanceId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          executions.add(mapRow(rs));
        }
      }
      return executions;
    } catch (SQLException e) {
      throw new AgentPersistenceException("Failed to load AgentExecutions for process instance "
          + processInstanceId, e);
    }
  }

  @Override
  public void deleteByExecutionId(String executionId) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement deleteEvents = connection.prepareStatement(
            "DELETE FROM FLUXNOVA_AGENT_EVENT WHERE EXECUTION_ID = ?")) {
          deleteEvents.setString(1, executionId);
          deleteEvents.executeUpdate();
        }
        try (PreparedStatement deleteExecution = connection.prepareStatement(
            "DELETE FROM FLUXNOVA_AGENT_EXECUTION WHERE EXECUTION_ID = ?")) {
          deleteExecution.setString(1, executionId);
          deleteExecution.executeUpdate();
        }
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw new AgentPersistenceException("Failed to delete AgentExecution " + executionId, e);
      }
    } catch (SQLException e) {
      throw new AgentPersistenceException("Failed to open transaction to delete AgentExecution " + executionId, e);
    }
  }

  @Override
  public boolean markRunningIfStarting(String executionId) {
    String sql = "UPDATE FLUXNOVA_AGENT_EXECUTION SET STATE_ = ?, UPDATED_AT = ? WHERE EXECUTION_ID = ? AND STATE_ = ?";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, AgentExecutionState.RUNNING.name());
      ps.setTimestamp(2, Timestamp.from(Instant.now()));
      ps.setString(3, executionId);
      ps.setString(4, AgentExecutionState.STARTING.name());
      return ps.executeUpdate() > 0;
    } catch (SQLException e) {
      throw new AgentPersistenceException("Failed to mark execution " + executionId + " running", e);
    }
  }

  @Override
  public void updateState(AgentExecution execution) {
    String sql = "UPDATE FLUXNOVA_AGENT_EXECUTION SET STATE_ = ?, ERROR_JSON = ?, UPDATED_AT = ? "
        + "WHERE EXECUTION_ID = ?";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, execution.getState().name());
      ps.setString(2, execution.getErrorJson());
      ps.setTimestamp(3, Timestamp.from(Instant.now()));
      ps.setString(4, execution.getExecutionId());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new AgentPersistenceException("Failed to update state for execution " + execution.getExecutionId(), e);
    }
  }

  @Override
  public boolean applyTransitionIfNewEvent(AgentExecution updated, String eventId, Integer seq, String eventType,
      String rawPayload) {
    String insertEventSql = "INSERT INTO FLUXNOVA_AGENT_EVENT (EVENT_ID, EXECUTION_ID, SEQ, TYPE_, RECEIVED_AT, "
        + "RAW_PAYLOAD) VALUES (?,?,?,?,?,?)";
    String updateExecutionSql = "UPDATE FLUXNOVA_AGENT_EXECUTION SET STATE_ = ?, LAST_EVENT_ID = ?, LAST_SEQ = ?, "
        + "BUSINESS_OUTCOME = ?, RESULT_JSON = ?, ERROR_JSON = ?, WAIT_JSON = ?, SEQUENCE_GAP = ?, UPDATED_AT = ? "
        + "WHERE EXECUTION_ID = ?";

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement insertEvent = connection.prepareStatement(insertEventSql)) {
          insertEvent.setString(1, eventId);
          insertEvent.setString(2, updated.getExecutionId());
          setNullableInt(insertEvent, 3, seq);
          insertEvent.setString(4, eventType);
          insertEvent.setTimestamp(5, Timestamp.from(Instant.now()));
          insertEvent.setString(6, rawPayload);
          insertEvent.executeUpdate();
        }

        Instant now = Instant.now();
        try (PreparedStatement updateExecution = connection.prepareStatement(updateExecutionSql)) {
          updateExecution.setString(1, updated.getState().name());
          updateExecution.setString(2, updated.getLastEventId());
          setNullableInt(updateExecution, 3, updated.getLastSeq());
          updateExecution.setString(4, updated.getBusinessOutcome());
          updateExecution.setString(5, updated.getResultJson());
          updateExecution.setString(6, updated.getErrorJson());
          updateExecution.setString(7, updated.getWaitJson());
          updateExecution.setBoolean(8, updated.isSequenceGap());
          updateExecution.setTimestamp(9, Timestamp.from(now));
          updateExecution.setString(10, updated.getExecutionId());
          updateExecution.executeUpdate();
        }

        connection.commit();
        return true;
      } catch (SQLException e) {
        connection.rollback();
        if (isIntegrityViolation(e)) {
          LOG.info("Duplicate lifecycle event {} for execution {} - already processed", eventId,
              updated.getExecutionId());
          return false;
        }
        throw new AgentPersistenceException(
            "Failed to apply transition for execution " + updated.getExecutionId(), e);
      }
    } catch (SQLException e) {
      throw new AgentPersistenceException(
          "Failed to open transaction for execution " + updated.getExecutionId(), e);
    }
  }

  private boolean isIntegrityViolation(SQLException e) {
    for (Throwable t = e; t instanceof SQLException; t = t.getCause()) {
      String sqlState = ((SQLException) t).getSQLState();
      if (sqlState != null && sqlState.startsWith(SQLSTATE_INTEGRITY_VIOLATION_CLASS)) {
        return true;
      }
    }
    return false;
  }

  private void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
    if (value == null) {
      ps.setNull(index, java.sql.Types.INTEGER);
    } else {
      ps.setInt(index, value);
    }
  }

  private AgentExecution mapRow(ResultSet rs) throws SQLException {
    AgentExecution execution = new AgentExecution();
    execution.setExecutionId(rs.getString("EXECUTION_ID"));
    execution.setProcessInstanceId(rs.getString("PROCESS_INSTANCE_ID"));
    execution.setActivityId(rs.getString("ACTIVITY_ID"));
    execution.setActivityInstanceId(rs.getString("ACTIVITY_INSTANCE_ID"));
    execution.setExternalTaskId(rs.getString("EXTERNAL_TASK_ID"));
    execution.setAgentRef(rs.getString("AGENT_REF"));
    execution.setSessionId(rs.getString("SESSION_ID"));
    execution.setState(AgentExecutionState.valueOf(rs.getString("STATE_")));
    execution.setLastEventId(rs.getString("LAST_EVENT_ID"));
    int lastSeq = rs.getInt("LAST_SEQ");
    execution.setLastSeq(rs.wasNull() ? null : lastSeq);
    execution.setBusinessOutcome(rs.getString("BUSINESS_OUTCOME"));
    execution.setResultJson(rs.getString("RESULT_JSON"));
    execution.setErrorJson(rs.getString("ERROR_JSON"));
    execution.setWaitJson(rs.getString("WAIT_JSON"));
    execution.setSequenceGap(rs.getBoolean("SEQUENCE_GAP"));
    Timestamp createdAt = rs.getTimestamp("CREATED_AT");
    execution.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
    Timestamp updatedAt = rs.getTimestamp("UPDATED_AT");
    execution.setUpdatedAt(updatedAt == null ? null : updatedAt.toInstant());
    return execution;
  }

}
