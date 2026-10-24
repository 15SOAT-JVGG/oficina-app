package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

@DisplayName("Migração Flyway do ciclo canônico da OS")
class FlywayWorkOrderLifecycleMigrationIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final UUID APPROVED_ID = UUID.randomUUID();
    private static final UUID IN_PROGRESS_ID = UUID.randomUUID();
    private static final UUID CANCELLED_ID = UUID.randomUUID();
    private static final UUID APPROVED_HISTORY_ID = UUID.randomUUID();
    private static final UUID APPROVED_PREVIOUS_HISTORY_ID = UUID.randomUUID();
    private static final UUID COMPLETED_HISTORY_ID = UUID.randomUUID();
    private static final UUID CANCELLED_HISTORY_ID = UUID.randomUUID();

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void createLegacySchema() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS oficina_mecanica CASCADE");
            statement.execute("CREATE SCHEMA oficina_mecanica");
            statement.execute("""
                    CREATE TABLE oficina_mecanica.work_order (
                        work_order_id UUID PRIMARY KEY,
                        status VARCHAR(20) NOT NULL,
                        opened_at TIMESTAMP NOT NULL,
                        closed_at TIMESTAMP,
                        created_at TIMESTAMPTZ NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE oficina_mecanica.work_order_history (
                        work_order_history_id UUID PRIMARY KEY,
                        work_order_id UUID NOT NULL,
                        previous_status VARCHAR(20),
                        new_status VARCHAR(20) NOT NULL,
                        changed_at TIMESTAMP NOT NULL
                    )
                    """);
        }
        insertWorkOrder(APPROVED_ID, "APPROVED", LocalDateTime.of(2026, 1, 10, 10, 0));
        insertWorkOrder(IN_PROGRESS_ID, "IN_PROGRESS", null);
        insertWorkOrder(CANCELLED_ID, "CANCELLED", null);
        insertHistory(
                APPROVED_HISTORY_ID,
                APPROVED_ID,
                "WAITING_APPROVAL",
                "APPROVED");
        insertHistory(
                APPROVED_PREVIOUS_HISTORY_ID,
                IN_PROGRESS_ID,
                "APPROVED",
                "IN_PROGRESS");
        insertHistory(
                COMPLETED_HISTORY_ID,
                CANCELLED_ID,
                "IN_PROGRESS",
                "COMPLETED");
        insertHistory(
                CANCELLED_HISTORY_ID,
                CANCELLED_ID,
                "COMPLETED",
                "CANCELLED");
    }

    @Nested
    @DisplayName("migração")
    class Migration {

        @Test
        @DisplayName("migra e preserva os valores legados sob restrições canônicas")
        void migratesLegacyStatusesWithAuditColumnsAndConstraints() throws Exception {
            migrate();

            assertEquals("IN_PROGRESS", statusOf(APPROVED_ID));
            assertEquals("APPROVED", stringColumn("work_order", APPROVED_ID, "legacy_status"));
            assertEquals("COMPLETED", statusOf(CANCELLED_ID));
            assertEquals("CANCELLED", stringColumn("work_order", CANCELLED_ID, "legacy_status"));
            assertEquals(
                    "APPROVED",
                    historyColumn(APPROVED_HISTORY_ID, "legacy_new_status"));
            assertEquals(
                    "APPROVED",
                    historyColumn(APPROVED_PREVIOUS_HISTORY_ID, "legacy_previous_status"));
            assertEquals(
                    "IN_PROGRESS",
                    historyColumn(APPROVED_PREVIOUS_HISTORY_ID, "previous_status"));
            assertEquals(
                    "CANCELLED",
                    historyColumn(CANCELLED_HISTORY_ID, "legacy_new_status"));
            assertEquals(
                    "COMPLETED",
                    historyColumn(COMPLETED_HISTORY_ID, "new_status"));
            assertTrue(timestampColumn(CANCELLED_ID, "cancelled_at") != null);

            assertThrows(
                    SQLException.class,
                    () -> insertWorkOrder(UUID.randomUUID(), "APPROVED", LocalDateTime.now()));
            assertThrows(
                    SQLException.class,
                    () -> insertWorkOrder(UUID.randomUUID(), "CANCELLED", LocalDateTime.now()));
            assertThrows(
                    SQLException.class,
                    () -> insertHistory(
                            UUID.randomUUID(),
                            APPROVED_ID,
                            "WAITING_APPROVAL",
                            "APPROVED"));
        }

        @Test
        @DisplayName("execuções repetidas preservam os dados já migrados")
        void repeatedExecutionIsSafe() throws Exception {
            migrate();
            migrate();

            assertEquals("IN_PROGRESS", statusOf(APPROVED_ID));
            assertEquals("APPROVED", stringColumn("work_order", APPROVED_ID, "legacy_status"));
            assertEquals("COMPLETED", statusOf(CANCELLED_ID));
            assertEquals("CANCELLED", stringColumn("work_order", CANCELLED_ID, "legacy_status"));
        }
    }

    @Nested
    @DisplayName("rollback")
    class Rollback {

        @Test
        @DisplayName("restaura estados históricos e remove os metadados da migração")
        void rollbackRestoresLegacyStatusesAndHistory() throws Exception {
            migrate();

            UUID rejectedAfterMigration = UUID.randomUUID();
            UUID rejectedHistoryAfterMigration = UUID.randomUUID();
            insertWorkOrder(rejectedAfterMigration, "COMPLETED", LocalDateTime.now());
            setCancelledAt(rejectedAfterMigration);
            insertHistory(
                    rejectedHistoryAfterMigration,
                    rejectedAfterMigration,
                    "WAITING_APPROVAL",
                    "COMPLETED");

            // Os rollbacks desfazem as versões da mais nova para a mais antiga; sem isso o Flyway
            // recusaria reaplicar a V2 por baixo de uma versão posterior ainda registrada.
            executeResource("db/rollback/U3__add_customer_active_status.sql");
            executeResource("db/rollback/U2__migrate_work_order_status_lifecycle.sql");

            assertEquals("APPROVED", statusOf(APPROVED_ID));
            assertEquals("CANCELLED", statusOf(CANCELLED_ID));
            assertNull(timestampColumn(CANCELLED_ID, "closed_at"));
            assertEquals(
                    "APPROVED",
                    historyColumn(APPROVED_HISTORY_ID, "new_status"));
            assertEquals(
                    "APPROVED",
                    historyColumn(APPROVED_PREVIOUS_HISTORY_ID, "previous_status"));
            assertEquals(
                    "CANCELLED",
                    historyColumn(CANCELLED_HISTORY_ID, "new_status"));
            assertEquals(
                    "COMPLETED",
                    historyColumn(COMPLETED_HISTORY_ID, "new_status"));
            assertEquals("CANCELLED", statusOf(rejectedAfterMigration));
            assertEquals(
                    "CANCELLED",
                    historyColumn(rejectedHistoryAfterMigration, "new_status"));
            assertFalse(columnExists("work_order", "cancelled_at"));
            assertFalse(columnExists("work_order", "legacy_status"));
            assertFalse(migrationVersionApplied("2"));

            migrate();

            assertEquals("IN_PROGRESS", statusOf(APPROVED_ID));
            assertEquals("COMPLETED", statusOf(CANCELLED_ID));
            assertTrue(migrationVersionApplied("2"));
        }

        @Test
        @DisplayName("também suporta um banco criado diretamente no contrato canônico")
        void rollbackSupportsFreshCanonicalSchema() throws Exception {
            try (Connection connection = connection();
                    Statement statement = connection.createStatement()) {
                statement.execute("""
                        ALTER TABLE oficina_mecanica.work_order
                        ADD COLUMN cancelled_at TIMESTAMP
                        """);
            }
            UUID rejected = UUID.randomUUID();
            insertWorkOrder(rejected, "COMPLETED", LocalDateTime.now());
            setCancelledAt(rejected);

            executeResource("db/rollback/U2__migrate_work_order_status_lifecycle.sql");

            assertEquals("CANCELLED", statusOf(rejected));
            assertFalse(columnExists("work_order", "cancelled_at"));
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .schemas("oficina_mecanica")
                .defaultSchema("oficina_mecanica")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .migrate();
    }

    private void executeResource(String path) throws IOException, SQLException {
        try (InputStream input = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Recurso não encontrado: " + path);
            }
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection connection = connection();
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    private void insertWorkOrder(UUID id, String status, LocalDateTime closedAt)
            throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO oficina_mecanica.work_order (
                            work_order_id,
                            status,
                            opened_at,
                            closed_at,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, TIMESTAMP '2026-01-10 08:00:00', ?, NOW(), NOW())
                        """)) {
            statement.setObject(1, id);
            statement.setString(2, status);
            statement.setObject(3, closedAt);
            statement.executeUpdate();
        }
    }

    private void insertHistory(
            UUID id,
            UUID workOrderId,
            String previousStatus,
            String newStatus
    ) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO oficina_mecanica.work_order_history (
                            work_order_history_id,
                            work_order_id,
                            previous_status,
                            new_status,
                            changed_at
                        ) VALUES (?, ?, ?, ?, NOW())
                        """)) {
            statement.setObject(1, id);
            statement.setObject(2, workOrderId);
            statement.setString(3, previousStatus);
            statement.setString(4, newStatus);
            statement.executeUpdate();
        }
    }

    private void setCancelledAt(UUID id) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE oficina_mecanica.work_order
                        SET cancelled_at = NOW()
                        WHERE work_order_id = ?
                        """)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    private String statusOf(UUID id) throws SQLException {
        return stringColumn("work_order", id, "status");
    }

    private String stringColumn(String table, UUID id, String column) throws SQLException {
        return queryString(
                "SELECT " + column + " FROM oficina_mecanica." + table
                        + " WHERE work_order_id = ?",
                id);
    }

    private String historyColumn(UUID id, String column) throws SQLException {
        return queryString(
                "SELECT " + column + " FROM oficina_mecanica.work_order_history"
                        + " WHERE work_order_history_id = ?",
                id);
    }

    private String queryString(String sql, UUID id) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private LocalDateTime timestampColumn(UUID id, String column) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + column + " FROM oficina_mecanica.work_order"
                                + " WHERE work_order_id = ?")) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getObject(1, LocalDateTime.class);
            }
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_schema = 'oficina_mecanica'
                              AND table_name = ?
                              AND column_name = ?
                        )
                        """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private boolean migrationVersionApplied(String version) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM oficina_mecanica.flyway_schema_history
                            WHERE version = ?
                              AND success
                        )
                        """)) {
            statement.setString(1, version);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }
}
