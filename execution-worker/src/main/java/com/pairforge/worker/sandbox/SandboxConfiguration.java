package com.pairforge.worker.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pairforge.worker.execution.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pairforge.sandbox.enabled", havingValue = "true")
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfiguration {
    @Bean DockerExecutionRunner dockerExecutionRunner(SandboxProperties properties, WorkerProperties worker, ObjectMapper mapper) {
        if (worker.cleanupTimeoutMs() < 10000)
            throw new IllegalArgumentException("Docker sandbox requires a cleanup timeout of at least 10000 ms");
        return new DockerExecutionRunner(properties, new DockerCommandClient(properties.dockerExecutable()), mapper);
    }
}
