package com.pairforge.api.execution;

import com.pairforge.api.common.ApiException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(ExecutionAccess.Properties.class)
public class ExecutionAccess {
    @ConfigurationProperties("pairforge.execution-access")
    public record Properties(@DefaultValue("false") boolean restricted, @DefaultValue Set<UUID> users,
                             Path enabledFile) {
        public Properties { users = users == null ? Set.of() : Set.copyOf(users); }
    }
    private final Properties properties;
    private final boolean production;
    private final Path enabledFile;
    public ExecutionAccess(Properties properties, Environment environment) {
        this.properties = properties;
        this.production = environment.matchesProfiles("prod");
        this.enabledFile = production ? Path.of("/run/pairforge-operations/execution-enabled") : properties.enabledFile();
    }
    public void requireApproved(UUID user) {
        // A production override cannot disable admission. Empty membership denies all.
        if ((production || properties.restricted()) && !properties.users().contains(user))
            throw new ApiException(403, "EXECUTION_RESTRICTED", "Execution is restricted to approved demo testers");
        // /run is cleared on reboot. Operators open admission only after worker readiness.
        if (enabledFile != null && !Files.isRegularFile(enabledFile, LinkOption.NOFOLLOW_LINKS))
            throw new ApiException(503, "EXECUTION_UNAVAILABLE", "Execution is temporarily unavailable");
    }
}
