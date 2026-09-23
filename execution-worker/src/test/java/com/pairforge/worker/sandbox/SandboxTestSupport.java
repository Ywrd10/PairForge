package com.pairforge.worker.sandbox;

import java.nio.file.Path;
import java.util.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

public final class SandboxTestSupport {
    private SandboxTestSupport() {}
    public static SandboxProperties properties(Path root, String namespace, Map<String, Object> overrides) throws Exception {
        var values = new HashMap<String, Object>();
        values.put("enabled", true); values.put("workspace-root", root.toAbsolutePath().toString()); values.put("namespace", namespace);
        for (String language : List.of("java", "python")) {
            String image = System.getenv("PAIRFORGE_SANDBOX_" + language.toUpperCase(Locale.ROOT) + "_IMAGE");
            if (image == null || image.isBlank()) {
                var output = new BoundedOutputCollector(4096);
                var result = new DockerCommandClient("docker").execute(List.of("image", "inspect", "--format", "{{.Id}}", "pairforge-sandbox-" + language + ":m10"), 10000, output);
                if (result.exitCode() != 0 || result.timedOut()) throw new IllegalStateException("Build required sandbox images with scripts/prepare-sandbox.ps1; tests must not skip");
                image = output.stdout().trim();
            }
            values.put(language + "-image", image);
        }
        values.putAll(overrides);
        return bind(values);
    }
    static SandboxProperties bind(Map<String, Object> values) {
        var prefixed = new HashMap<String, Object>(); values.forEach((key, value) -> prefixed.put("pairforge.sandbox." + key, value));
        return new Binder(new MapConfigurationPropertySource(prefixed)).bindOrCreate("pairforge.sandbox", Bindable.of(SandboxProperties.class));
    }
    public static String docker(String... args) throws Exception {
        var output = new BoundedOutputCollector(262144);
        var result = new DockerCommandClient("docker").execute(List.of(args), 10000, output);
        if (result.exitCode() != 0 || result.timedOut() || output.overflow()) throw new IllegalStateException("Test Docker operation failed: " + output.stderr());
        return output.stdout();
    }
}
