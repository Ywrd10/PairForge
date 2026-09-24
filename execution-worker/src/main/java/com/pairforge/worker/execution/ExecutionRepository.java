package com.pairforge.worker.execution;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Worker-owned JDBC queries; migrations remain owned by the API. Each statement commits before returning. */
public class ExecutionRepository {
    public record State(UUID id, String status, long revision) {
        public boolean terminal() { return Set.of("SUCCEEDED", "FAILED", "TIMED_OUT").contains(status); }
    }
    public record Job(UUID id, String language, String source, Instant deadline, long revision,
                      Instant createdAt, Instant startedAt) {}
    private final JdbcTemplate jdbc;
    public ExecutionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public ExecutionEvent event(UUID id) {
        return jdbc.queryForObject("select id,room_id,status,state_revision from executions where id=?",
                (r,n) -> new ExecutionEvent(1,r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getLong(4)),id);
    }
    public Optional<State> state(UUID id) {
        return jdbc.query("select id,status,state_revision from executions where id=?",
                (r, n) -> new State(r.getObject(1, UUID.class), r.getString(2), r.getLong(3)), id).stream().findFirst();
    }
    public Optional<Job> claim(UUID id, long deadlineMs) {
        return jdbc.query("""
                update executions set status='RUNNING', state_revision=state_revision+1,
                    started_at=greatest(clock_timestamp(),created_at),
                    deadline_at=greatest(clock_timestamp(),created_at) + (? * interval '1 millisecond')
                where id=? and status='QUEUED'
                returning id,language,source_code,deadline_at,state_revision,created_at,started_at
                """, (r, n) -> new Job(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                r.getTimestamp(4).toInstant(), r.getLong(5), r.getTimestamp(6).toInstant(),
                r.getTimestamp(7).toInstant()), deadlineMs, id).stream().findFirst();
    }
    public int complete(UUID id, long revision, ExecutionResult result) {
        return jdbc.update("""
                update executions set status=?, stdout=?, stderr=?, exit_code=?, duration_ms=?,
                    failure_reason=?, output_truncated=?, completed_at=greatest(clock_timestamp(),started_at,created_at),
                    state_revision=state_revision+1
                where id=? and status='RUNNING' and state_revision=?
                """, result.status().name(), result.stdout(), result.stderr(), result.exitCode(), result.durationMs(),
                result.failureReason() == null ? null : result.failureReason().name(), result.outputTruncated(), id, revision);
    }
    public List<State> interrupted() {
        // One worker normally leaves at most one RUNNING job. Bound startup recovery work defensively.
        var rows = jdbc.query("select id,status,state_revision from executions where status='RUNNING' order by id limit 101",
                (r, n) -> new State(r.getObject(1, UUID.class), r.getString(2), r.getLong(3)));
        if (rows.size() > 100) throw new IllegalStateException("Too many interrupted jobs for automatic startup recovery");
        return rows;
    }
}
