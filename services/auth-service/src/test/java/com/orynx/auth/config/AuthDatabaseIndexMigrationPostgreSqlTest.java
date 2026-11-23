package com.orynx.auth.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies auth-service startup index migration idempotently creates idx_users_email
 * on PostgreSQL.
 */
class AuthDatabaseIndexMigrationPostgreSqlTest {

    private static JdbcTemplate jdbcTemplate;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void setUpAll() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getenv().getOrDefault(
                "SPRING_DATASOURCE_URL",
                "jdbc:postgresql://localhost:5439/orynx_test"
        ));
        dataSource.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "testuser"));
        dataSource.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "testpass"));

        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    @DisplayName("auth-service DatabaseIndexMigration idempotently creates idx_users_email on PostgreSQL")
    void authDatabaseIndexMigrationCreatesEmailIndexIdempotently() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id BIGSERIAL PRIMARY KEY,
                email VARCHAR(255),
                password VARCHAR(255),
                role VARCHAR(50)
            )
        """);

        DatabaseIndexMigration migration = new DatabaseIndexMigration(jdbcTemplate, transactionTemplate);
        migration.run(new DefaultApplicationArguments());
        migration.run(new DefaultApplicationArguments());

        Set<String> indexes = jdbcTemplate.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'users'",
                        String.class
                )
                .stream()
                .collect(Collectors.toSet());

        assertThat(indexes).contains(DatabaseIndexMigration.INDEX_NAME);
    }
}
