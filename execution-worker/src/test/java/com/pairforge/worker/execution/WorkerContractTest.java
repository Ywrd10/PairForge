package com.pairforge.worker.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerContractTest {
    final WorkerProperties disabled = new WorkerProperties(false, false, 30000, 2000, 100);
    final ConnectionFactory connection = mock(ConnectionFactory.class);
    final ObjectMapper mapper = new ObjectMapper();
    final ExecutionConsumer consumer = new ExecutionConsumer(connection, mapper, null, disabled);
    @AfterEach void close() { consumer.close(); }
    Message json(String value) {
        var p = new MessageProperties(); p.setContentType("application/json");
        return new Message(value.getBytes(java.nio.charset.StandardCharsets.UTF_8), p);
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "null", "[]", "{\"schemaVersion\":2,\"executionId\":\"x\"}",
            "{\"schemaVersion\":1.0,\"executionId\":\"x\"}", "{\"schemaVersion\":1,\"executionId\":1}",
            "{\"schemaVersion\":1,\"executionId\":\"1-1-1-1-1\"}"})
    void rejectsInvalidJobs(String body) { assertThatThrownBy(() -> consumer.decode(json(body))).isInstanceOf(Exception.class); }
    @Test void strictIdOnlyEnvelopeRejectsTrailingDuplicateAndOversizedInput() throws Exception {
        UUID id = UUID.randomUUID(); String body = "{\"schemaVersion\":1,\"executionId\":\"" + id + "\"}";
        assertThat(consumer.decode(json(body))).isEqualTo(id);
        assertThatThrownBy(() -> consumer.decode(json(body + "{}"))).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> consumer.decode(json(body.replace("}", ",\"source\":\"forged\"}")))).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> consumer.decode(json(body.replace("}", ",\"schemaVersion\":1}")))).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> consumer.decode(json(" ".repeat(1025)))).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> consumer.decode(new Message(body.getBytes()))).isInstanceOf(Exception.class);
    }
    @Test void enablingWithoutRunnerOrPredecessorConfirmationFailsClosed() {
        var config = new WorkerConfiguration(); var beans = new DefaultListableBeanFactory();
        assertThatThrownBy(() -> config.workerExecution(connection, mapper, mock(ExecutionRepository.class),
                beans.getBeanProvider(ExecutionRunner.class), new WorkerProperties(true, true, 30000, 2000, 100), mock(ExecutionEventPublisher.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("enabled, configured sandbox");
        beans.registerSingleton("testRunner", mock(ExecutionRunner.class));
        assertThatThrownBy(() -> config.workerExecution(connection, mapper, mock(ExecutionRepository.class),
                beans.getBeanProvider(ExecutionRunner.class), new WorkerProperties(true, false, 30000, 2000, 100), mock(ExecutionEventPublisher.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("previous worker");
    }
    @Test void resultRetentionUsesCombinedUtf8BytesAndRejectsInvalidText() {
        assertThatCode(() -> new ExecutionResult(ExecutionResult.Status.SUCCEEDED, "😀".repeat(16384), "", 0, 0L, null, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new ExecutionResult(ExecutionResult.Status.SUCCEEDED, "😀".repeat(16384), "x", 0, 0L, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionResult(ExecutionResult.Status.SUCCEEDED, "\0", "", 0, 0L, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionResult(ExecutionResult.Status.SUCCEEDED, "\ud800", "", 0, 0L, null, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
