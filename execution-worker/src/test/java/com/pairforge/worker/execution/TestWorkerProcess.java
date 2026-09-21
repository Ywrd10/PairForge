package com.pairforge.worker.execution;

import com.pairforge.worker.ExecutionWorkerApplication;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

/** A separate test-only JVM for real process-kill/redelivery verification. Never interprets source. */
public final class TestWorkerProcess {
    public static void main(String[] args) {
        boolean afterCommit = Boolean.parseBoolean(System.getenv("TEST_KILL_AFTER_COMMIT"));
        new SpringApplicationBuilder(ExecutionWorkerApplication.class).initializers(context -> {
            if (afterCommit) context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof ExecutionRepository)) return bean;
                    return new ExecutionRepository(context.getBean(JdbcTemplate.class)) {
                        @Override public int complete(UUID id, long revision, ExecutionResult result) {
                            int updated = super.complete(id, revision, result);
                            System.out.println("TEST_RESULT_COMMITTED");
                            try { new CountDownLatch(1).await(); }
                            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                            return updated;
                        }
                    };
                }
            });
                context.getBeanFactory().registerSingleton("testRunner", new ExecutionRunner() {
                    @Override public ExecutionResult run(ExecutionRepository.Job job) throws Exception {
                        if (afterCommit) return new ExecutionResult(ExecutionResult.Status.SUCCEEDED, "committed", "", 0, 1L, null, false);
                        System.out.println("TEST_RUNNER_ENTERED");
                        new CountDownLatch(1).await();
                        throw new IllegalStateException("Unreachable test runner return");
                    }
                    @Override public void stop(UUID id) {}
                });
            }).run("--pairforge.worker.enabled=true", "--pairforge.worker.previous-worker-stopped=true",
                        "--pairforge.worker.deadline-ms=60000", "--server.port=0", "--logging.level.root=WARN");
    }
}
