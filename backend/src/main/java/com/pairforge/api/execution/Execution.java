package com.pairforge.api.execution;

import com.pairforge.api.common.Language;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "executions")
public class Execution {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "room_id", nullable = false, updatable = false)
    private UUID roomId;

    @Column(name = "submitted_by", nullable = false, updatable = false)
    private UUID submittedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Language language;

    @Column(name = "source_code", nullable = false, updatable = false, columnDefinition = "text")
    private String sourceCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExecutionStatus status;

    @Column(nullable = false, columnDefinition = "text")
    private String stdout;

    @Column(nullable = false, columnDefinition = "text")
    private String stderr;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "state_revision", nullable = false)
    private long stateRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 32)
    private FailureReason failureReason;

    @Column(name = "output_truncated", nullable = false)
    private boolean outputTruncated;

    protected Execution() {}

    public Execution(UUID roomId, UUID submittedBy, Language language, String sourceCode) {
        this.id = UUID.randomUUID();
        this.roomId = Objects.requireNonNull(roomId, "roomId");
        this.submittedBy = Objects.requireNonNull(submittedBy, "submittedBy");
        this.language = Objects.requireNonNull(language, "language");
        this.sourceCode = Objects.requireNonNull(sourceCode, "sourceCode");
        this.status = ExecutionStatus.QUEUED;
        this.stdout = "";
        this.stderr = "";
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public UUID getRoomId() { return roomId; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Language getLanguage() { return language; }
    public String getSourceCode() { return sourceCode; }
    public ExecutionStatus getStatus() { return status; }
    public String getStdout() { return stdout; }
    public String getStderr() { return stderr; }
    public Integer getExitCode() { return exitCode; }
    public Long getDurationMs() { return durationMs; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public long getStateRevision() { return stateRevision; }
    public FailureReason getFailureReason() { return failureReason; }
    public boolean isOutputTruncated() { return outputTruncated; }
}
