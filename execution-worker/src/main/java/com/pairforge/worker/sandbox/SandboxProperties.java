package com.pairforge.worker.sandbox;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pairforge.sandbox")
public record SandboxProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("docker") String dockerExecutable, @DefaultValue("pairforge") String namespace,
        @DefaultValue(".tmp/execution-workspaces") Path workspaceRoot,
        @DefaultValue("") String javaImage, @DefaultValue("") String pythonImage,
        @DefaultValue("512") int javaMemoryMib, @DefaultValue("128") int pythonMemoryMib,
        @DefaultValue("128") int javaPids, @DefaultValue("32") int pythonPids,
        @DefaultValue("1.0") double cpus, @DefaultValue("32") int workspaceMib,
        @DefaultValue("8") int tempMib, @DefaultValue("4") int shmMib,
        @DefaultValue("65536") int sourceBytes, @DefaultValue("65536") int outputBytes,
        @DefaultValue("1500") long commandTimeoutMs, @DefaultValue("10000") long preparationMs,
        @DefaultValue("10000") long compilationMs, @DefaultValue("5000") long runtimeMs,
        @DefaultValue("2000") long reconciliationMs) {
    public SandboxProperties {
        if (dockerExecutable == null || dockerExecutable.isBlank() || namespace == null || !namespace.matches("[a-z][a-z0-9-]{0,31}")
                || workspaceRoot == null || javaMemoryMib < 128 || javaMemoryMib > 1024 || pythonMemoryMib < 32 || pythonMemoryMib > 512
                || javaPids < 32 || javaPids > 256 || pythonPids < 8 || pythonPids > 128 || !Double.isFinite(cpus) || cpus < 0.1 || cpus > 2
                || workspaceMib < 1 || workspaceMib > 64 || tempMib < 1 || tempMib > 16 || shmMib < 1 || shmMib > 16
                || sourceBytes < 1 || sourceBytes > 65536 || outputBytes < 1 || outputBytes > 65536
                || commandTimeoutMs < 100 || commandTimeoutMs > 3000 || preparationMs < 1000 || preparationMs > 15000
                || compilationMs < 100 || compilationMs > 15000 || runtimeMs < 100 || runtimeMs > 10000
                || reconciliationMs < 500 || reconciliationMs > 10000)
            throw new IllegalArgumentException("Invalid sandbox configuration");
        if (enabled && (!pinned(javaImage) || !pinned(pythonImage)))
            throw new IllegalArgumentException("Sandbox images must be pinned by digest or local image ID");
    }
    private static boolean pinned(String image) {
        return image != null && image.matches("(?:[a-zA-Z0-9./:_-]+@)?sha256:[a-f0-9]{64}");
    }
    public String image(String language) { return switch (language) { case "JAVA" -> javaImage; case "PYTHON" -> pythonImage; default -> throw new IllegalArgumentException("Unsupported language"); }; }
    public long memory(String language) { return (language.equals("JAVA") ? javaMemoryMib : pythonMemoryMib) * 1048576L; }
    public int pids(String language) { return language.equals("JAVA") ? javaPids : pythonPids; }
}
