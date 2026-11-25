package com.contentanalytics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applies the real Flyway migrations to a real PostgreSQL instance.
 *
 * The main test profile uses H2 + Hibernate DDL because the migrations use
 * Postgres-only features (gen_random_uuid, partial indexes). That is fast, but
 * it cannot catch migration syntax errors or entity/schema drift. This test is
 * therefore opt-in for CI machines with Docker:
 *
 *
 * RUN_POSTGRES_TESTS=true ./mvnw test -Dtest=FlywayMigrationTest
 *
 *
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
@DisplayName("Flyway migrations on PostgreSQL")
class FlywayMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("content_analytics_migrations")
                    .withUsername("test")
                    .withPassword("test");

    @Test
    @DisplayName("all migrations apply cleanly and validate against the resulting schema")
    void migrationsApplyAndValidate() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isPositive();

        // Throws on checksum mismatch, missing migration, or failed validation.
        flyway.validate();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema = 'public' AND table_name IN ('users', 'documents', 'payment_transactions')
                     ORDER BY table_name
                     """)) {
            StringBuilder tables = new StringBuilder();
            while (rs.next()) {
                tables.append(rs.getString(1)).append(',');
            }
            assertThat(tables.toString()).contains("users", "documents", "payment_transactions");
        }
    }
}
