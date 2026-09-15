package com.pairforge.api.persistence;

import com.pairforge.api.PairForgeApiApplication;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class FlywayMigrationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withPassword(UUID.randomUUID().toString());

    private String schema() { return "test_" + UUID.randomUUID().toString().replace("-", ""); }

    private Flyway flyway(String schema, String... locations) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations(locations).cleanDisabled(true).load();
    }

    @Test
    void migratesCleanDatabaseAndRepeatStartupPreservesData() throws Exception {
        String schema = schema();
        Flyway migration = flyway(schema, "classpath:db/migration");
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        migration.validate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO " + schema + ".users VALUES ('" + UUID.randomUUID()
                    + "', 'migration@example.com', 'synthetic-hash', now())");
            assertThat(migration.migrate().migrationsExecuted).isZero();
            try (var result = statement.executeQuery("SELECT count(*) FROM " + schema + ".users")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            try (var result = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = '"
                    + schema + "' AND table_name IN ('users','rooms','room_members','executions')")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(4);
            }
        }
    }

    @Test
    void refusesChangedMigrationChecksum() throws Exception {
        String schema = schema();
        Flyway migration = flyway(schema, "classpath:db/migration");
        migration.migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("UPDATE " + schema + ".flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");
        }
        assertThatThrownBy(migration::migrate).isInstanceOf(FlywayException.class).hasMessageContaining("checksum");
    }

    @Test
    void schemaDriftPreventsApiStartup() throws Exception {
        String schema = schema();
        flyway(schema, "classpath:db/migration").migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + schema + ".users DROP COLUMN password_hash");
        }
        assertThatThrownBy(() -> {
            try (var context = startApi(schema, "classpath:db/migration")) {
                fail("API must not start with schema drift");
            }
        }).hasStackTraceContaining("missing column [password_hash]");
    }

    private org.springframework.context.ConfigurableApplicationContext startApi(String schema, String locations) {
        return new SpringApplicationBuilder(PairForgeApiApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.flyway.schemas=" + schema,
                "--spring.flyway.default-schema=" + schema,
                "--spring.jpa.properties.hibernate.default_schema=" + schema,
                "--spring.flyway.locations=" + locations,
                "--pairforge.auth.key-hex=" + UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""),
                "--pairforge.auth.bcrypt-cost=4",
                "--spring.rabbitmq.host=127.0.0.1", "--spring.rabbitmq.username=test",
                "--spring.rabbitmq.password=" + UUID.randomUUID(),
                "--spring.data.redis.host=127.0.0.1");
    }

    @Test
    void failedMigrationPreventsApiStartupAndRollsBackPartialDdl() throws Exception {
        String schema = schema();
        assertThatThrownBy(() -> {
            try (var context = startApi(schema, "classpath:db/migration,classpath:db/broken")) {
                fail("API must not start after a failed migration");
            }
        }).hasStackTraceContaining("V2__broken.sql");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT to_regclass('" + schema + ".partial_migration')")) {
            result.next();
            assertThat(result.getString(1)).isNull();
        }
    }
}
