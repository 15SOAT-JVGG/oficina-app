package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class FlywayEventJournalMigrationIT {
    @Test
    void createsJournalBeforeWorkOrderTableAndEnforcesProjectionInvariants() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas("oficina_mecanica").defaultSchema("oficina_mecanica")
                    .locations("classpath:db/migration").load().migrate();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                UUID event = UUID.randomUUID();
                UUID order = UUID.randomUUID();
                try (var insert = connection.prepareStatement("""
                        INSERT INTO oficina_mecanica.work_order_outbox(event_id, work_order_id, event_type, payload)
                        VALUES (?, ?, 'work-order.opened', '{}'::jsonb)
                        """)) {
                    insert.setObject(1, event);
                    insert.setObject(2, order);
                    assertEquals(1, insert.executeUpdate());
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO oficina_mecanica.work_order_estimate_projection
                            (work_order_id, opening_event_id, saga_id, correlation_id, status)
                        VALUES (?, ?, ?, ?, 'AGUARDANDO')
                        """)) {
                    insert.setObject(1, order);
                    insert.setObject(2, event);
                    insert.setObject(3, UUID.randomUUID());
                    insert.setObject(4, UUID.randomUUID());
                    assertEquals(1, insert.executeUpdate());
                }
                try (var update = connection.createStatement()) {
                    assertThrows(SQLException.class, () -> update.executeUpdate("""
                            UPDATE oficina_mecanica.work_order_estimate_projection SET status = 'CRIADO'
                            """));
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO oficina_mecanica.work_order_inbox(event_id, work_order_id, event_type)
                        VALUES (?, ?, 'estimate.created')
                        """)) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, order);
                    assertEquals(1, insert.executeUpdate());
                    assertThrows(SQLException.class, insert::executeUpdate);
                }
            }
        }
    }
}
