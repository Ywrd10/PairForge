package com.pairforge.worker.sandbox;

import com.fasterxml.jackson.databind.*;
import com.pairforge.worker.execution.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Trusted orchestration only. Compilation and programs are Docker exec processes, never host processes. */
public class DockerExecutionRunner implements ExecutionRunner {
    static final String OWNER = "io.pairforge.sandbox", JOB = "io.pairforge.execution", DEADLINE = "io.pairforge.deadline";
    private static final List<String> PROXY_VARIABLES = List.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "FTP_PROXY",
            "http_proxy", "https_proxy", "all_proxy", "no_proxy", "ftp_proxy");
    private final SandboxProperties p;
    private final DockerCommandClient docker;
    private final ObjectMapper mapper;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final Path root;
    private final Map<String, String> images = new HashMap<>();
    private final ConcurrentMap<UUID, Activity> active = new ConcurrentHashMap<>();
    private final Object cleanupLock = new Object();
    private volatile boolean initialized;
    private boolean posixWorkspace;
    private static final class Activity {
        final UUID id;
        final long expires;
        final AtomicBoolean cancelled = new AtomicBoolean(), expired = new AtomicBoolean();
        Activity(UUID id, long remaining) { this.id = id; expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, remaining)); }
    }
    public DockerExecutionRunner(SandboxProperties properties, DockerCommandClient docker, ObjectMapper mapper, io.micrometer.core.instrument.MeterRegistry metrics) {
        this.p = properties; this.docker = docker; this.mapper = mapper; this.metrics = metrics;
        root = p.workspaceRoot().toAbsolutePath().normalize().resolve(p.namespace());
    }
    @Override public void initialize() throws Exception {
        JsonNode info = json("info", "--format", "{{json .}}");
        if (!info.path("OSType").asText().equals("linux") || !info.path("CgroupVersion").asText().equals("2")
                || !info.path("SecurityOptions").toString().contains("name=seccomp"))
            throw new IllegalStateException("Linux cgroup v2 and seccomp are required");
        Files.createDirectories(root);
        if (!root.toRealPath().equals(root) || root.toString().contains(",")) throw new IllegalStateException("Unsafe workspace root");
        posixWorkspace = Files.getFileStore(root).supportsFileAttributeView("posix");
        if (posixWorkspace) Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        reconcile(true);
        for (String language : List.of("JAVA", "PYTHON")) {
            JsonNode image = json("image", "inspect", p.image(language)).get(0);
            if (!image.path("Os").asText().equals("linux") || image.path("Config").path("Volumes").size() != 0)
                throw new IllegalStateException("Unsupported sandbox image");
            images.put(language, image.path("Id").asText());
        }
        // Prove actual kernel controls for each image before admitting submissions.
        for (String language : List.of("JAVA", "PYTHON")) {
            UUID id = UUID.randomUUID(); var activity = new Activity(id, p.preparationMs());
            active.put(id, activity);
            try {
                prepare(activity, language, "", Instant.now().plusMillis(p.preparationMs()));
                if (language.equals("JAVA")) {
                    control("exec", name(id), "/opt/java/openjdk/bin/javac", "-J-Xmx256m", "-version");
                    control("exec", name(id), "/opt/java/openjdk/bin/java", "-XX:+UseSerialGC", "-Xmx" + p.javaMemoryMib() + "m", "-version");
                } else control("exec", name(id), "/usr/local/bin/python3", "-I", "-S", "-B", "--version");
            }
            finally { finish(id); }
        }
        initialized = true;
    }
    @Override public ExecutionResult run(ExecutionRepository.Job job) throws Exception {
        if (!initialized) throw new IllegalStateException("Sandbox preflight has not passed");
        p.image(job.language());
        if (job.source() == null || job.source().indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(job.source())
                || job.source().getBytes(StandardCharsets.UTF_8).length > p.sourceBytes()) throw new IllegalArgumentException("Invalid source snapshot");
        long remaining = Math.min(300000, Duration.between(Instant.now(), job.deadline()).toMillis());
        var activity = new Activity(job.id(), remaining);
        if (active.putIfAbsent(job.id(), activity) != null) throw new IllegalStateException("Execution already active");
        long started = System.nanoTime();
        var capture = new BoundedOutputCollector(p.outputBytes());
        try {
            long preparing = System.nanoTime();
            try { prepare(activity, job.language(), job.source(), job.deadline()); }
            finally { recordPhase(job.language(), "preparation", preparing); }
            if (job.language().equals("JAVA")) {
                var compiled = measuredPhase(job.language(), "compilation", activity, p.compilationMs(), capture, "/opt/java/openjdk/bin/javac", "-J-Xmx256m", "-proc:none", "-encoding", "UTF-8", "-d", "/work", "/source/Main.java");
                ExecutionResult result = outcome(activity, compiled, capture, true, started);
                if (result != null) return result;
            }
            DockerCommandClient.Result executed = job.language().equals("JAVA")
                    ? measuredPhase(job.language(), "runtime", activity, p.runtimeMs(), capture, "/opt/java/openjdk/bin/java", "-XX:+UseSerialGC", "-Xmx" + p.javaMemoryMib() + "m", "-XX:+ExitOnOutOfMemoryError", "-cp", "/work", "Main")
                    : measuredPhase(job.language(), "runtime", activity, p.runtimeMs(), capture, "/usr/local/bin/python3", "-I", "-S", "-B", "/source/main.py");
            ExecutionResult result = outcome(activity, executed, capture, false, started);
            return result == null ? result(ExecutionResult.Status.SUCCEEDED, null, capture, executed.exitCode(), started) : result;
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if (error instanceof TimeoutException || activity.expired.get() || remaining(activity) <= 0)
                return result(ExecutionResult.Status.TIMED_OUT, null, capture, null, started);
            throw error;
        } finally {
            finish(job.id());
        }
    }
    private void finish(UUID id) throws Exception {
        // A shutdown interrupt must not make every Docker cleanup wait fail immediately.
        // Preserve it after bounded cleanup; never report a successful run on interruption.
        boolean interrupted = Thread.interrupted();
        try { stop(id); }
        finally { active.remove(id); if (interrupted) Thread.currentThread().interrupt(); }
    }
    private ExecutionResult outcome(Activity a, DockerCommandClient.Result command, BoundedOutputCollector output, boolean compile, long started) throws Exception {
        if (output.overflow()) return result(ExecutionResult.Status.FAILED, ExecutionResult.FailureReason.OUTPUT_LIMIT, output, null, started);
        if (command.timedOut() || a.expired.get()) return result(ExecutionResult.Status.TIMED_OUT, null, output, null, started);
        if (a.cancelled.get()) throw new IllegalStateException("Execution stopped");
        JsonNode container = inspect(a.id);
        boolean oom = container.path("State").path("OOMKilled").asBoolean();
        if (container.path("State").path("Running").asBoolean()) {
            // cgroup metadata is read-only and cannot be forged by program output/exit codes.
            String events = control("exec", name(a.id), "/bin/cat", "/sys/fs/cgroup/memory.events");
            oom |= Arrays.stream(events.split("\\R")).anyMatch(line -> line.matches("oom_kill [1-9][0-9]*"));
        }
        if (oom) return result(ExecutionResult.Status.FAILED, ExecutionResult.FailureReason.MEMORY_LIMIT, output, command.exitCode(), started);
        if (command.exitCode() != 0 || !container.path("State").path("Running").asBoolean())
            return result(ExecutionResult.Status.FAILED, compile ? ExecutionResult.FailureReason.COMPILATION_ERROR : ExecutionResult.FailureReason.RUNTIME_ERROR, output, command.exitCode(), started);
        return null;
    }
    private ExecutionResult result(ExecutionResult.Status status, ExecutionResult.FailureReason reason, BoundedOutputCollector output, Integer exit, long started) {
        return new ExecutionResult(status, output.stdout(), output.stderr(), exit,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), reason, output.overflow());
    }
    private DockerCommandClient.Result measuredPhase(String language, String phase, Activity a, long limit, BoundedOutputCollector capture, String... command) throws Exception {
        long started = System.nanoTime();
        try { return phase(a, limit, capture, command); }
        finally { recordPhase(language, phase, started); }
    }
    private void recordPhase(String language, String phase, long started) {
        metrics.timer("pairforge.execution.phase", "language", language, "phase", phase)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
    private DockerCommandClient.Result phase(Activity a, long limit, BoundedOutputCollector capture, String... command) throws Exception {
        check(a);
        var args = new ArrayList<>(List.of("exec", name(a.id))); args.addAll(List.of(command));
        return docker.execute(args, Math.min(limit, remaining(a)), capture);
    }
    private void prepare(Activity a, String language, String source, Instant deadline) throws Exception {
        long preparationEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(p.preparationMs());
        synchronized (a) {
            check(a);
            Path directory = directory(a.id);
            Files.createDirectory(directory);
            if (posixWorkspace) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            Path file = directory.resolve(filename(language));
            Files.writeString(file, source, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            if (posixWorkspace) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        }
        step(a, preparationEnd, createArguments(a.id, language, deadline));
        synchronized (a) { check(a); verify(inspect(a.id), language); }
        step(a, preparationEnd, List.of("start", name(a.id)));
        String actual = step(a, preparationEnd, List.of("exec", name(a.id), "/bin/sh", "-c",
                "id -u; cat /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.swap.max /sys/fs/cgroup/pids.max /sys/fs/cgroup/cpu.max; cat /proc/self/status"));
        verifyKernel(actual, language);
    }
    private String step(Activity a, long preparationEnd, List<String> args) throws Exception {
        synchronized (a) {
            check(a);
            long time = Math.min(p.commandTimeoutMs(), Math.min(remaining(a), TimeUnit.NANOSECONDS.toMillis(preparationEnd - System.nanoTime())));
            var output = new BoundedOutputCollector(262144);
            var result = docker.execute(args, time, output);
            if (result.timedOut() && (remaining(a) <= 0 || System.nanoTime() >= preparationEnd))
                throw new TimeoutException("Sandbox preparation deadline");
            if (result.exitCode() != 0 || result.timedOut() || output.overflow()) throw new IllegalStateException("Sandbox preparation failed");
            return output.stdout();
        }
    }
    List<String> createArguments(UUID id, String language, Instant deadline) {
        var args = new ArrayList<>(List.of("create", "--pull=never", "--name", name(id), "--label", OWNER + "=" + p.namespace(),
                "--label", JOB + "=" + id, "--label", DEADLINE + "=" + deadline.toEpochMilli(),
                "--user", "10001:10001", "--network", "none", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true", "--cgroupns", "private", "--ipc", "private", "--restart", "no",
                "--memory", Long.toString(p.memory(language)), "--memory-swap", Long.toString(p.memory(language)),
                "--cpus", Double.toString(p.cpus()), "--pids-limit", Integer.toString(p.pids(language)),
                "--ulimit", "nofile=128:128", "--ulimit", "core=0:0", "--shm-size", p.shmMib() + "m",
                "--tmpfs", "/work:" + tmpfs(p.workspaceMib()), "--tmpfs", "/tmp:" + tmpfs(p.tempMib()),
                "--mount", "type=bind,src=" + directory(id).resolve(filename(language)) + ",dst=/source/" + filename(language) + ",readonly",
                "--workdir", "/work", "--env", "HOME=/work", "--env", "TMPDIR=/tmp", "--log-driver", "none"));
        // Docker CLI otherwise copies proxy settings (possibly credentials) from its host config.
        for (String variable : PROXY_VARIABLES) { args.add("--env"); args.add(variable + "="); }
        args.addAll(List.of("--entrypoint", "/bin/sleep", images.getOrDefault(language, p.image(language)), "infinity"));
        return List.copyOf(args);
    }
    private static String tmpfs(int mib) { return "rw,noexec,nosuid,nodev,size=" + mib + "m,uid=10001,gid=10001,mode=700"; }
    void verify(JsonNode c, String language) {
        JsonNode h = c.path("HostConfig"), config = c.path("Config");
        boolean valid = h.path("ReadonlyRootfs").asBoolean() && !h.path("Privileged").asBoolean()
                && h.path("NetworkMode").asText().equals("none") && h.path("CgroupnsMode").asText().equals("private")
                && h.path("PidMode").asText().isEmpty() && h.path("IpcMode").asText().equals("private") && h.path("UTSMode").asText().isEmpty()
                && h.path("Devices").size() == 0 && h.path("DeviceRequests").size() == 0 && h.path("DeviceCgroupRules").size() == 0
                && h.path("Binds").size() == 0 && h.path("VolumesFrom").size() == 0
                && h.path("Memory").asLong() == p.memory(language) && h.path("MemorySwap").asLong() == p.memory(language)
                && h.path("PidsLimit").asInt() == p.pids(language) && h.path("NanoCpus").asLong() == (long) (p.cpus() * 1_000_000_000L)
                && h.path("ShmSize").asLong() == p.shmMib() * 1048576L && h.path("LogConfig").path("Type").asText().equals("none")
                && h.path("RestartPolicy").path("Name").asText().equals("no") && config.path("User").asText().equals("10001:10001")
                && !config.path("OpenStdin").asBoolean() && !config.path("Tty").asBoolean()
                && c.path("Image").asText().equals(images.get(language)) && h.path("CapAdd").size() == 0
                && h.path("CapDrop").toString().equals("[\"ALL\"]")
                && h.path("SecurityOpt").toString().equals("[\"no-new-privileges:true\"]")
                && h.path("Tmpfs").size() == 2 && h.path("Tmpfs").path("/work").asText().equals(tmpfs(p.workspaceMib()))
                && h.path("Tmpfs").path("/tmp").asText().equals(tmpfs(p.tempMib()));
        var environment = new HashSet<String>(); config.path("Env").forEach(value -> environment.add(value.asText()));
        for (String variable : PROXY_VARIABLES) valid &= environment.contains(variable + "=");
        int binds = 0;
        for (JsonNode mount : c.path("Mounts")) {
            if (mount.path("Type").asText().equals("bind")) {
                binds++;
                valid &= !mount.path("RW").asBoolean() && mount.path("Destination").asText().equals("/source/" + filename(language));
            } else valid &= mount.path("Type").asText().equals("tmpfs");
        }
        if (!valid || binds != 1) throw new IllegalStateException("Sandbox controls were not applied");
    }
    void verifyKernel(String actual, String language) {
        List<String> lines = actual.lines().toList();
        if (lines.size() < 6 || !lines.get(0).equals("10001") || !lines.get(1).equals(Long.toString(p.memory(language)))
                || !lines.get(2).equals("0") || !lines.get(3).equals(Integer.toString(p.pids(language)))
                || !actual.matches("(?s).*CapEff:\\s+0+\\R.*") || !actual.matches("(?s).*NoNewPrivs:\\s+1\\R.*")
                || !actual.matches("(?s).*Seccomp:\\s+2\\R.*")) throw new IllegalStateException("Kernel sandbox controls unavailable");
        String[] cpu = lines.get(4).split(" ");
        if (cpu.length != 2 || cpu[0].equals("max") || Math.abs(Double.parseDouble(cpu[0]) / Double.parseDouble(cpu[1]) - p.cpus()) > 0.00001)
            throw new IllegalStateException("CPU quota unavailable");
    }
    @Override public void stop(UUID id) throws Exception {
        try { cleanup(id); }
        catch (Exception error) {
            metrics.counter("pairforge.execution.cleanup.failures", "operation", "sandbox_stop").increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("executionId", id)
                    .addKeyValue("type", error.getClass().getSimpleName()).log("Sandbox cleanup failed");
            throw error;
        }
    }
    private void cleanup(UUID id) throws Exception {
        Activity a = active.get(id);
        if (a != null) a.cancelled.set(true);
        synchronized (cleanupLock) { synchronized (a == null ? this : a) {
            String found = control("ps", "-aq", "--filter", "name=^/" + name(id) + "$", "--no-trunc").trim();
            if (!found.isEmpty()) {
                JsonNode c = inspect(id);
                if (!c.path("Config").path("Labels").path(OWNER).asText().equals(p.namespace())
                        || !c.path("Config").path("Labels").path(JOB).asText().equals(id.toString()))
                    throw new IllegalStateException("Refusing to remove unowned container");
                control("rm", "--force", name(id));
            }
            removeDirectory(id);
        } }
    }
    @Override public long reconciliationMs() { return p.reconciliationMs(); }
    @Override public void reconcile() throws Exception { reconcile(false); }
    private void reconcile(boolean startup) throws Exception {
        String list = control("ps", "-a", "--filter", "label=" + OWNER + "=" + p.namespace(), "--format", "{{.Names}}");
        List<String> ids = list.lines().filter(s -> !s.isBlank()).toList();
        if (ids.size() > 100) throw new IllegalStateException("Too many sandbox resources");
        for (String containerName : ids) {
            String prefix = "pf-" + p.namespace() + "-";
            if (!containerName.startsWith(prefix)) throw new IllegalStateException("Sandbox label/name mismatch");
            UUID id = UUID.fromString(containerName.substring(prefix.length()));
            if (!containerName.equals(name(id))) throw new IllegalStateException("Sandbox name mismatch");
            Activity a = active.get(id);
            if (startup || a == null || remaining(a) <= 0) {
                if (a != null) a.expired.set(true);
                stop(id);
            }
        }
        try (var directories = Files.list(root)) {
            var paths = directories.limit(101).toList();
            if (paths.size() > 100) throw new IllegalStateException("Too many sandbox workspaces");
            for (Path path : paths) {
                UUID id = UUID.fromString(path.getFileName().toString());
                if (!path.getFileName().toString().equals(id.toString())) throw new IllegalStateException("Noncanonical sandbox workspace");
                synchronized (cleanupLock) { if (!active.containsKey(id)) removeDirectory(id); }
            }
        }
    }
    private void removeDirectory(UUID id) throws Exception {
        Path dir = directory(id);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        if (!dir.toRealPath().equals(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Unsafe workspace");
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (!List.of("Main.java", "main.py").contains(file.getFileName().toString()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalStateException("Unexpected workspace entry");
                Files.delete(file);
            }
        }
        Files.delete(dir);
    }
    private Path directory(UUID id) { return root.resolve(id.toString()); }
    private String name(UUID id) { return "pf-" + p.namespace() + "-" + id; }
    private static String filename(String language) { return language.equals("JAVA") ? "Main.java" : "main.py"; }
    private static long remaining(Activity a) { return TimeUnit.NANOSECONDS.toMillis(a.expires - System.nanoTime()); }
    private static void check(Activity a) throws TimeoutException {
        if (a.expired.get() || remaining(a) <= 0) throw new TimeoutException("Sandbox deadline expired");
        if (a.cancelled.get()) throw new IllegalStateException("Sandbox cancelled");
    }
    private JsonNode inspect(UUID id) throws Exception { return json("inspect", name(id)).get(0); }
    private JsonNode json(String... args) throws Exception { return mapper.readTree(control(args)); }
    private String control(String... args) throws Exception {
        var output = new BoundedOutputCollector(262144);
        var result = docker.execute(List.of(args), p.commandTimeoutMs(), output);
        if (result.exitCode() != 0 || result.timedOut() || output.overflow()) throw new IllegalStateException("Docker control operation failed");
        return output.stdout();
    }
}
