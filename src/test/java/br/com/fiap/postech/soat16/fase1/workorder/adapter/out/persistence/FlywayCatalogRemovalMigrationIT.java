package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class FlywayCatalogRemovalMigrationIT {
    @Test
    void removesLocalCatalogWhileKeepingHistoricalReferences() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            UUID partId = UUID.randomUUID();
            UUID serviceId = UUID.randomUUID();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA oficina_mecanica");
                statement.execute("""
                        CREATE TABLE oficina_mecanica.parts (
                            part_id UUID PRIMARY KEY
                        )
                        """);
                statement.execute("""
                        CREATE TABLE oficina_mecanica.service_item (
                            service_item_id UUID PRIMARY KEY
                        )
                        """);
                statement.execute("""
                        CREATE TABLE oficina_mecanica.estimate_item (
                            estimate_item_id UUID PRIMARY KEY,
                            part_id UUID REFERENCES oficina_mecanica.parts(part_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE oficina_mecanica.work_order_service (
                            work_order_service_id UUID PRIMARY KEY,
                            service_item_id UUID REFERENCES oficina_mecanica.service_item(service_item_id)
                        )
                        """);
                try (var insert = connection.prepareStatement(
                        "INSERT INTO oficina_mecanica.parts VALUES (?)")) {
                    insert.setObject(1, partId);
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement(
                        "INSERT INTO oficina_mecanica.service_item VALUES (?)")) {
                    insert.setObject(1, serviceId);
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement(
                        "INSERT INTO oficina_mecanica.estimate_item VALUES (?, ?)")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, partId);
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement(
                        "INSERT INTO oficina_mecanica.work_order_service VALUES (?, ?)")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, serviceId);
                    insert.executeUpdate();
                }
            }

            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas("oficina_mecanica").defaultSchema("oficina_mecanica")
                    .baselineOnMigrate(true).baselineVersion("4")
                    .locations("classpath:db/migration").load().migrate();

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("""
                        SELECT to_regclass('oficina_mecanica.parts') IS NULL,
                               to_regclass('oficina_mecanica.service_item') IS NULL
                        """)) {
                    assertTrue(result.next());
                    assertTrue(result.getBoolean(1));
                    assertTrue(result.getBoolean(2));
                }
                try (var result = statement.executeQuery("""
                        SELECT part_id FROM oficina_mecanica.estimate_item
                        """)) {
                    assertTrue(result.next());
                    assertEquals(partId, result.getObject(1, UUID.class));
                    assertFalse(result.next());
                }
                try (var result = statement.executeQuery("""
                        SELECT service_item_id FROM oficina_mecanica.work_order_service
                        """)) {
                    assertTrue(result.next());
                    assertEquals(serviceId, result.getObject(1, UUID.class));
                    assertFalse(result.next());
                }
            }
        }
    }
}
