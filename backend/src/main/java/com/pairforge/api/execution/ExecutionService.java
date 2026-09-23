package com.pairforge.api.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.pairforge.api.common.ApiException;
import com.pairforge.api.room.RoomService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.pairforge.api.execution.ExecutionDtos.*;

@Service
@ConditionalOnWebApplication
public class ExecutionService {
    private final ExecutionRepository executions;
    private final RoomService rooms;
    private final ExecutionAdmission admission;
    private final ExecutionJobPublisher publisher;
    private final ExecutionProperties properties;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    private final ExecutionEventPublisher events;
    // Covers count + insert + commit, never publication; this MVP runs one API instance.
    private final ReentrantLock admissionLock = new ReentrantLock();
    public ExecutionService(ExecutionRepository executions, RoomService rooms, ExecutionAdmission admission,
                            ExecutionJobPublisher publisher, ExecutionProperties properties,
                            PlatformTransactionManager manager, MeterRegistry metrics, ExecutionEventPublisher events) {
        this.executions = executions; this.rooms = rooms; this.admission = admission; this.publisher = publisher;
        this.properties = properties; this.transactions = new TransactionTemplate(manager); this.metrics = metrics;
        this.transactions.setTimeout(5);
        this.events = events;
    }
    public Receipt submit(UUID user, UUID room, JsonNode body) {
        rooms.get(user, room);
        var request = Submission.parse(body, properties.sourceBytes());
        admission.check(user);
        try {
            if (!admissionLock.tryLock(properties.admissionWaitMs(), TimeUnit.MILLISECONDS)) throw busy();
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw busy(); }
        var execution = new Execution(room, user, request.language(), request.source());
        try {
            transactions.executeWithoutResult(status -> {
                rooms.get(user, room);
                if (executions.countByStatusIn(List.of(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING)) >= properties.maxOutstanding()) throw busy();
                executions.saveAndFlush(execution);
            });
        } catch (ApiException error) { throw error; }
        catch (RuntimeException error) { throw unknown(execution.getId(), error); }
        finally { admissionLock.unlock(); }

        // PostgreSQL commit has completed. No database transaction spans broker I/O.
        events.publish(new ExecutionEvent(1, execution.getId(), room, "QUEUED", execution.getStateRevision()));
        var failure = publisher.publish(execution.getId());
        if (failure != null) {
            try {
                transactions.executeWithoutResult(status -> executions.failQueued(execution.getId(), failure,
                        Instant.now().truncatedTo(ChronoUnit.MICROS)));
            } catch (RuntimeException error) { throw unknown(execution.getId(), error); }
        }
        final Execution current;
        try { current = executions.findById(execution.getId()).orElseThrow(); }
        catch (RuntimeException error) { throw unknown(execution.getId(), error); }
        if (failure != null) events.publish(new ExecutionEvent(1, current.getId(), current.getRoomId(),
                current.getStatus().name(), current.getStateRevision()));
        if (current.getStatus() == ExecutionStatus.FAILED && (current.getFailureReason() == FailureReason.DISPATCH_FAILED
                || current.getFailureReason() == FailureReason.DISPATCH_UNCONFIRMED)) {
            throw new ExecutionDispatchException(current.getId(), current.getStatus(), current.getFailureReason());
        }
        return Receipt.from(current);
    }
    public History history(UUID user, UUID room, int page, int size) {
        rooms.get(user, room);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new ApiException(400, "INVALID_INPUT", "Invalid execution pagination");
        var result = executions.history(room, PageRequest.of(page, size));
        return new History(result.getContent(), page, size, result.hasNext());
    }
    public Detail detail(UUID user, UUID id) {
        var execution = executions.findById(id).orElseThrow(ExecutionService::inaccessible);
        try { rooms.get(user, execution.getRoomId()); }
        catch (ApiException error) { if (error.status() == 404) throw inaccessible(); throw error; }
        return Detail.from(execution);
    }
    private ExecutionDispatchException unknown(UUID id, RuntimeException error) {
        metrics.counter("pairforge.execution.persistence.unknown").increment();
        org.slf4j.LoggerFactory.getLogger(ExecutionService.class).warn("Execution persistence outcome unknown executionId={} type={}", id, error.getClass().getSimpleName());
        return new ExecutionDispatchException(id, null, null);
    }
    private static ApiException busy() { return new ApiException(503, "EXECUTION_CAPACITY", "Execution capacity is temporarily unavailable", 5L); }
    private static ApiException inaccessible() { return new ApiException(404, "EXECUTION_NOT_FOUND", "Execution is unavailable"); }
}
