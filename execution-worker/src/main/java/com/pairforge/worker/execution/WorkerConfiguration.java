package com.pairforge.worker.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfiguration {
    @Bean ExecutionRepository executionRepository(JdbcTemplate jdbc) { return new ExecutionRepository(jdbc); }
    @Bean(name = "workerExecution", destroyMethod = "close")
    ExecutionConsumer workerExecution(ConnectionFactory connection, ObjectMapper mapper, ExecutionRepository repository,
                                      ObjectProvider<ExecutionRunner> runners, WorkerProperties properties) {
        ExecutionProcessor processor = null;
        if (properties.enabled()) {
            ExecutionRunner runner = runners.getIfAvailable();
            if (runner == null) throw new IllegalStateException("Consumption requires an ExecutionRunner; no runtime runner ships in Milestone 9");
            if (!properties.previousWorkerStopped())
                throw new IllegalStateException("Confirm the previous worker is stopped before enabling startup recovery");
            processor = new ExecutionProcessor(repository, runner, properties);
        }
        return new ExecutionConsumer(connection, mapper, processor, properties);
    }
}
