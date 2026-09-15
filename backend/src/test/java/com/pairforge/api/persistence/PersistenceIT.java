package com.pairforge.api.persistence;

import com.pairforge.api.common.Language;
import com.pairforge.api.execution.*;
import com.pairforge.api.room.*;
import com.pairforge.api.user.*;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistenceIT {
    private static final String WORKER = "pairforge_worker_test";
    private static final String WORKER_PASSWORD = UUID.randomUUID().toString();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("pairforge_test").withUsername("pairforge_test")
            .withPassword(UUID.randomUUID().toString())
            .withEnv("WORKER_DB_USER", WORKER)
            .withEnv("WORKER_DB_PASSWORD", WORKER_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of("..", "scripts", "provision-worker.sql").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/010-worker.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired ExecutionRepository executions;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    private User user() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "synthetic-hash"));
    }

    private Room room(User owner) {
        return rooms.saveAndFlush(new Room(owner.getId(), "Room", Language.JAVA, "a".repeat(64)));
    }

    private Execution execution() {
        User owner = user();
        Room room = room(owner);
        return executions.saveAndFlush(new Execution(room.getId(), owner.getId(), Language.PYTHON, "print('hi')"));
    }

    @Test
    void roundTripsModelsAndThreeMembersWithoutLeakingOtherRooms() {
        User owner = user();
        Room room = room(owner);
        User second = user();
        User third = user();
        members.saveAllAndFlush(java.util.List.of(new RoomMember(room.getId(), owner.getId()),
                new RoomMember(room.getId(), second.getId()), new RoomMember(room.getId(), third.getId())));
        Room unrelated = room(user());
        Execution execution = executions.saveAndFlush(
                new Execution(room.getId(), second.getId(), Language.PYTHON, "print('héllo')"));
        Instant created = execution.getCreatedAt();
        entityManager.clear();

        assertThat(users.findByEmail(owner.getEmail())).get().extracting(User::getId).isEqualTo(owner.getId());
        assertThat(rooms.findById(room.getId())).get().extracting(Room::getInvitationTokenHash).isEqualTo("a".repeat(64));
        assertThat(members.countByIdRoomId(room.getId())).isEqualTo(3);
        assertThat(members.findById(new RoomMemberId(room.getId(), third.getId()))).isPresent();
        assertThat(rooms.findRoomsForMember(second.getId(), PageRequest.of(0, 10)))
                .extracting(Room::getId).containsExactly(room.getId()).doesNotContain(unrelated.getId());
        Execution loaded = executions.findById(execution.getId()).orElseThrow();
        assertThat(loaded.getCreatedAt()).isEqualTo(created);
        assertThat(loaded.getSourceCode()).isEqualTo("print('héllo')");
        assertThat(loaded.getLanguage()).isEqualTo(Language.PYTHON);
        assertThat(loaded.getSubmittedBy()).isEqualTo(second.getId());
        assertThat(loaded.getStatus()).isEqualTo(ExecutionStatus.QUEUED);
        assertThat(loaded.getStdout()).isEmpty();
        assertThat(loaded.getStderr()).isEmpty();
        assertThat(loaded.getStateRevision()).isZero();
        assertThat(loaded.getExitCode()).isNull();
        assertThat(loaded.getDeadlineAt()).isNull();
        assertThat(loaded.isOutputTruncated()).isFalse();
    }

    @Test
    void databaseEnforcesNormalizedEmailUniqueness() {
        users.saveAndFlush(new User(" Alice@Example.com ", "synthetic-hash"));
        assertThatThrownBy(() -> users.saveAndFlush(new User("alice@example.COM", "another-hash")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsDuplicateMembership() {
        User owner = user();
        Room room = room(owner);
        jdbc.update("INSERT INTO room_members VALUES (?, ?, now())", room.getId(), owner.getId());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO room_members VALUES (?, ?, now())",
                room.getId(), owner.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"room_id = '00000000-0000-0000-0000-000000000001'",
            "user_id = '00000000-0000-0000-0000-000000000001'", "joined_at = NULL"})
    void databaseRejectsInvalidMembership(String assignment) {
        User owner = user();
        Room room = room(owner);
        members.saveAndFlush(new RoomMember(room.getId(), owner.getId()));
        assertThatThrownBy(() -> jdbc.update("UPDATE room_members SET " + assignment + " WHERE room_id = ?", room.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"email = 'UPPER@example.com'", "email = ' spaced@example.com '",
            "email = ''", "email = NULL", "password_hash = NULL", "password_hash = ''",
            "email = repeat('a', 255)", "password_hash = repeat('x', 256)"})
    void databaseRejectsInvalidUsersEvenWhenJpaIsBypassed(String assignment) {
        User user = user();
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET " + assignment + " WHERE id = ?", user.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name = ''", "name = '  '", "name = repeat('x', 121)",
            "owner_id = '00000000-0000-0000-0000-000000000001'", "owner_id = NULL",
            "language = 'RUBY'", "invitation_token_hash = 'raw-token'",
            "invitation_token_hash = NULL", "updated_at = created_at - interval '1 second'"})
    void databaseRejectsInvalidRooms(String assignment) {
        Room room = room(user());
        assertThatThrownBy(() -> jdbc.update("UPDATE rooms SET " + assignment + " WHERE id = ?", room.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "room_id = '00000000-0000-0000-0000-000000000001'",
            "submitted_by = '00000000-0000-0000-0000-000000000001'",
            "language = 'RUBY'", "status = 'UNKNOWN'", "source_code = NULL",
            "source_code = repeat('é', 32769)", "stdout = repeat('é', 32768), stderr = 'a'",
            "stdout = NULL", "stderr = NULL", "duration_ms = -1", "state_revision = -1",
            "failure_reason = 'UNKNOWN'", "started_at = created_at - interval '1 second'",
            "completed_at = created_at - interval '1 second'",
            "deadline_at = created_at - interval '1 second'"})
    void databaseRejectsInvalidExecutionData(String assignment) {
        Execution execution = execution();
        assertThatThrownBy(() -> jdbc.update("UPDATE executions SET " + assignment + " WHERE id = ?", execution.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void acceptsExactUtf8BudgetsAndMapsNullableResultFields() {
        Execution execution = execution();
        jdbc.update("""
                UPDATE executions SET source_code = repeat('é', 32768),
                    stdout = repeat('é', 16384), stderr = repeat('é', 16384),
                    status = 'FAILED', failure_reason = 'OUTPUT_LIMIT',
                    output_truncated = true, duration_ms = 25, state_revision = 1,
                    started_at = created_at, completed_at = created_at + interval '1 second',
                    deadline_at = created_at + interval '5 seconds'
                WHERE id = ?
                """, execution.getId());
        entityManager.clear();
        Execution loaded = executions.findById(execution.getId()).orElseThrow();
        assertThat(loaded.getFailureReason()).isEqualTo(FailureReason.OUTPUT_LIMIT);
        assertThat(loaded.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(loaded.getDurationMs()).isEqualTo(25);
        assertThat(loaded.getExitCode()).isNull();
        assertThat(loaded.getStateRevision()).isEqualTo(1);
        assertThat(loaded.isOutputTruncated()).isTrue();
        assertThat(loaded.getCompletedAt()).isAfter(loaded.getStartedAt());
        assertThat(loaded.getDeadlineAt()).isAfter(loaded.getCompletedAt());
    }

    @Test
    void historyUsesStableIdTieBreakerAndPagination() {
        User owner = user();
        Room room = room(owner);
        Execution first = executions.saveAndFlush(new Execution(room.getId(), owner.getId(), Language.JAVA, ""));
        Execution second = executions.saveAndFlush(new Execution(room.getId(), owner.getId(), Language.JAVA, ""));
        jdbc.update("UPDATE executions SET created_at = '2026-01-01T00:00:00Z' WHERE room_id = ?", room.getId());
        entityManager.clear();
        var expected = jdbc.queryForList("SELECT id FROM executions WHERE room_id = ? ORDER BY created_at DESC, id DESC",
                UUID.class, room.getId());
        var page = executions.findByRoomIdOrderByCreatedAtDescIdDesc(room.getId(), PageRequest.of(0, 1));
        assertThat(page.hasNext()).isTrue();
        assertThat(page).extracting(Execution::getId).containsExactly(expected.getFirst());
        assertThat(executions.findByRoomIdOrderByCreatedAtDescIdDesc(room.getId(), PageRequest.of(1, 1)))
                .extracting(Execution::getId).containsExactly(expected.getLast());
        assertThat(expected).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void deletingReferencedUsersIsRestricted() {
        User owner = user();
        room(owner);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", owner.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void deletingRoomsWithHistoryIsRestricted() {
        Execution execution = execution();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM rooms WHERE id = ?", execution.getRoomId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void workerCanConnectButCannotReadSecretsWriteMembershipOrCreateSchema() throws Exception {
        // Run the same provisioning SQL again against an already migrated database.
        var provision = POSTGRES.execInContainer("sh", "-c",
                "psql -q -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -f /docker-entrypoint-initdb.d/010-worker.sql");
        assertThat(provision.getExitCode()).isZero();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), WORKER, WORKER_PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(connection.isValid(2)).isTrue();
            for (String sql : java.util.List.of("SELECT password_hash FROM users",
                    "SELECT * FROM executions", "UPDATE room_members SET joined_at = now()",
                    "CREATE TABLE forbidden (id int)", "CREATE SCHEMA forbidden",
                    "CREATE TEMP TABLE forbidden_temp (id int)")) {
                assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));
            }
        }
    }

    @Test
    void provisioningRefusesWorkerRoleWithOtherRoleMemberships() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("GRANT pg_read_all_data TO " + WORKER);
            try {
                var provision = POSTGRES.execInContainer("sh", "-c",
                        "psql -q -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -f /docker-entrypoint-initdb.d/010-worker.sql");
                assertThat(provision.getExitCode()).isNotZero();
                assertThat(provision.getStderr()).contains("Refusing worker role");
            } finally {
                statement.execute("REVOKE pg_read_all_data FROM " + WORKER);
            }
        }
    }

    @Test
    void repeatProvisioningRemovesColumnLevelPasswordHashAccess() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("GRANT SELECT (password_hash) ON users TO " + WORKER);
            try {
                var provision = POSTGRES.execInContainer("sh", "-c",
                        "psql -q -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -f /docker-entrypoint-initdb.d/010-worker.sql");
                assertThat(provision.getExitCode()).isZero();
                try (var workerConnection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), WORKER, WORKER_PASSWORD);
                     var workerStatement = workerConnection.createStatement()) {
                    assertThatThrownBy(() -> workerStatement.execute("SELECT password_hash FROM users"))
                            .isInstanceOf(SQLException.class)
                            .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));
                }
            } finally {
                statement.execute("REVOKE SELECT (password_hash) ON users FROM " + WORKER);
            }
        }
    }

    @Test
    void requiredIndexesExist() {
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class))
                .contains("room_members_user_idx", "executions_room_history_idx", "executions_status_deadline_idx");
    }
}
