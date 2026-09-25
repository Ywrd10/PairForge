package com.pairforge.api.execution;

import com.pairforge.api.common.ApiException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ExecutionAccessTest {
    @Test void productionCannotBeDisabledAndEmptyAllowlistDeniesAll() {
        var env = new MockEnvironment(); env.setActiveProfiles("prod");
        var access = new ExecutionAccess(new ExecutionAccess.Properties(false, Set.of(), null), env);
        assertThatThrownBy(() -> access.requireApproved(UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(403));
    }
    @Test void onlyApprovedIdentityCanExecuteAndConfigurationIsImmutable() {
        UUID approved = UUID.randomUUID();
        var ids = new java.util.HashSet<>(Set.of(approved));
        var access = new ExecutionAccess(new ExecutionAccess.Properties(true, ids, null), new MockEnvironment());
        ids.add(new UUID(0, 1));
        assertThatCode(() -> access.requireApproved(approved)).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requireApproved(new UUID(0, 1))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> access.requireApproved(UUID.randomUUID())).isInstanceOf(ApiException.class);
    }
    @Test void localDevelopmentRetainsExistingAdmission() {
        var access = new ExecutionAccess(new ExecutionAccess.Properties(false, null, null), new MockEnvironment());
        assertThatCode(() -> access.requireApproved(UUID.randomUUID())).doesNotThrowAnyException();
    }
    @Test void malformedApprovalConfigurationFailsBinding() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(ExecutionAccess.class)
                .withPropertyValues("pairforge.execution-access.restricted=true", "pairforge.execution-access.users=not-a-uuid")
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void operationalGateClosesImmediatelyWithoutRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        UUID approved = UUID.randomUUID();
        var marker = directory.resolve("enabled");
        var access = new ExecutionAccess(new ExecutionAccess.Properties(true, Set.of(approved), marker), new MockEnvironment());
        assertThatThrownBy(() -> access.requireApproved(approved)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(503));
        java.nio.file.Files.createFile(marker);
        assertThatCode(() -> access.requireApproved(approved)).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requireApproved(UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(403));
        java.nio.file.Files.delete(marker);
        assertThatThrownBy(() -> access.requireApproved(approved)).isInstanceOf(ApiException.class);
        java.nio.file.Files.createDirectory(marker);
        assertThatThrownBy(() -> access.requireApproved(approved)).isInstanceOf(ApiException.class);
    }
    @Test void emptyConfiguredAllowlistBindsAndDenies() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(ExecutionAccess.class)
                .withPropertyValues("pairforge.execution-access.restricted=true", "pairforge.execution-access.users=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThatThrownBy(() -> context.getBean(ExecutionAccess.class).requireApproved(UUID.randomUUID())).isInstanceOf(ApiException.class);
                });
    }
}
