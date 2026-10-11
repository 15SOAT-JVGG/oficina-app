package br.com.fiap.postech.soat16.fase1.customer.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

@DisplayName("Migração Flyway do status do cliente")
class FlywayCustomerStatusMigrationIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final UUID LEGACY_CUSTOMER_ID = UUID.randomUUID();

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
                    CREATE TABLE oficina_mecanica.customer (
                        customer_id UUID PRIMARY KEY,
                        first_name VARCHAR(255) NOT NULL,
                        phone_number VARCHAR(255) NOT NULL,
                        document VARCHAR(255) NOT NULL UNIQUE,
                        document_type VARCHAR(20) NOT NULL
                    )
                    """);
        }
        try (Connection connection = connection();
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO oficina_mecanica.customer
                            (customer_id, first_name, phone_number, document, document_type)
                        VALUES (?, 'Maria', '+5511999999999', '52998224725', 'CPF')
                        """)) {
            insert.setObject(1, LEGACY_CUSTOMER_ID);
            insert.executeUpdate();
        }
    }

    @Nested
    @DisplayName("migração")
    class Migration {

        @Test
        @DisplayName("mantém ativos os clientes cadastrados antes do status existir")
        void keepsExistingCustomersActive() throws Exception {
            migrate();

            assertTrue(isActive(LEGACY_CUSTOMER_ID));
        }

        @Test
        @DisplayName("cadastra ativo o cliente gravado sem informar o status")
        void defaultsNewCustomersToActive() throws Exception {
            migrate();
            UUID newCustomer = UUID.randomUUID();
            try (Connection connection = connection();
                    PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO oficina_mecanica.customer
                                (customer_id, first_name, phone_number, document, document_type)
                            VALUES (?, 'João', '+5511988888888', '11144477735', 'CPF')
                            """)) {
                insert.setObject(1, newCustomer);
                insert.executeUpdate();
            }

            assertTrue(isActive(newCustomer));
        }
    }

    @Nested
    @DisplayName("rollback")
    class Rollback {

        @Test
        @DisplayName("remove a coluna e o registro da versão, permitindo reaplicá-la")
        void rollbackRemovesColumnAndAllowsReapplying() throws Exception {
            migrate();

            executeResource("db/rollback/U3__add_customer_active_status.sql");

            assertFalse(columnExists("active"));

            migrate();

            assertTrue(isActive(LEGACY_CUSTOMER_ID));
        }
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("oficina_mecanica")
                .defaultSchema("oficina_mecanica")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .migrate();
    }

    private void executeResource(String path) throws IOException, SQLException {
        try (InputStream script = getClass().getClassLoader().getResourceAsStream(path);
                Connection connection = connection();
                Statement statement = connection.createStatement()) {
            if (script == null) {
                throw new IllegalStateException("Script não encontrado: " + path);
            }
            statement.execute(new String(script.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private boolean isActive(UUID customerId) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT active FROM oficina_mecanica.customer WHERE customer_id = ?")) {
            query.setObject(1, customerId);
            try (ResultSet result = query.executeQuery()) {
                return result.next() && result.getBoolean("active");
            }
        }
    }

    private boolean columnExists(String column) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement query = connection.prepareStatement("""
                        SELECT 1 FROM information_schema.columns
                        WHERE table_schema = 'oficina_mecanica' AND table_name = 'customer' AND column_name = ?
                        """)) {
            query.setString(1, column);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
