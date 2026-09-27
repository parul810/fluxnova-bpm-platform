package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.persistence;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the plugin's own Liquibase changelog against the engine's datasource,
 * independent of Camunda's own schema/version tracking (design doc section 7
 * and 9). Tracked in Liquibase's own {@code DATABASECHANGELOG} table, which
 * makes this idempotent across restarts and safe to run on every startup.
 */
public class SchemaInitializer {

  private static final Logger LOG = LoggerFactory.getLogger(SchemaInitializer.class);

  private static final String CHANGELOG_PATH = "db/changelog/db.changelog-master.xml";

  public void run(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      Database database = DatabaseFactory.getInstance()
          .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      Liquibase liquibase = new Liquibase(CHANGELOG_PATH, new ClassLoaderResourceAccessor(), database);
      liquibase.update("");
      LOG.info("agent-webhook-plugin schema is up to date");
    } catch (SQLException | LiquibaseException e) {
      throw new AgentPersistenceException("Failed to apply agent-webhook-plugin schema changelog", e);
    }
  }

}
