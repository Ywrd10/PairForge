package com.pairforge.api.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.pairforge.api.common.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

public final class ExecutionDtos {
    private ExecutionDtos() {}
    public record Submission(String source, Language language) {
        // A strict boundary avoids Jackson coercing numbers into source or language.
        static Submission parse(JsonNode body, int maxBytes) {
            if (body == null || !body.isObject() || body.size() != 2
                    || !body.path("source").isTextual() || !body.path("language").isTextual()) throw invalid();
            String source = body.get("source").textValue();
            if (source.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(source)) throw invalid();
            if (source.getBytes(StandardCharsets.UTF_8).length > maxBytes)
                throw new ApiException(413, "SOURCE_TOO_LARGE", "Submitted source exceeds the UTF-8 byte limit");
            final Language language;
            try { language = Language.valueOf(body.get("language").textValue()); }
            catch (IllegalArgumentException error) { throw invalid(); }
            return new Submission(source, language);
        }
        private static ApiException invalid() {
            return new ApiException(400, "INVALID_INPUT", "Provide source text and language JAVA or PYTHON only");
        }
    }
    public record Summary(UUID id, UUID roomId, UUID submittedBy, Language language, ExecutionStatus status,
                          Instant createdAt, Instant completedAt, long stateRevision, FailureReason failureReason) {}
    public record History(List<Summary> items, int page, int size, boolean hasNext) {}
    public record Detail(UUID id, UUID roomId, UUID submittedBy, Language language, String source,
                         ExecutionStatus status, String stdout, String stderr, Integer exitCode, Long durationMs,
                         Instant createdAt, Instant startedAt, Instant completedAt, Instant deadlineAt,
                         long stateRevision, FailureReason failureReason, boolean outputTruncated) {
        static Detail from(Execution e) {
            return new Detail(e.getId(), e.getRoomId(), e.getSubmittedBy(), e.getLanguage(), e.getSourceCode(),
                    e.getStatus(), e.getStdout(), e.getStderr(), e.getExitCode(), e.getDurationMs(), e.getCreatedAt(),
                    e.getStartedAt(), e.getCompletedAt(), e.getDeadlineAt(), e.getStateRevision(), e.getFailureReason(), e.isOutputTruncated());
        }
    }
    public record Receipt(UUID executionId, ExecutionStatus status, long stateRevision, FailureReason failureReason) {
        static Receipt from(Execution e) { return new Receipt(e.getId(), e.getStatus(), e.getStateRevision(), e.getFailureReason()); }
    }
}
