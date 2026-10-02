package com.pixelclub.teams.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Actual CLI always uses Docker; only a marked, existing local H2 file permits bypass. */
final class ColdGate {
    private static final Set<String> CONTAINERS = Set.of("pixelclub-arclight", "pixelclub-fabric", "pixelclub-dev");
    record State(boolean running, boolean restarting, boolean paused, String startedAt) {}
    interface Probe { State inspect(String container) throws Exception; }
    private final String container;
    private final Probe probe;
    private final State initial;

    private ColdGate(String container, Probe probe, State initial) { this.container = container; this.probe = probe; this.initial = initial; }
    static ColdGate docker(String container) throws Exception { return docker(container, ColdGate::inspectDocker); }
    static ColdGate docker(String container, Probe probe) throws Exception {
        if (!CONTAINERS.contains(container)) throw new IllegalArgumentException();
        State state = probe.inspect(container); stopped(state);
        return new ColdGate(container, probe, state);
    }
    static ColdGate fixture(Path rootArgument, String url, String driver) throws Exception {
        if (!rootArgument.isAbsolute() || network(rootArgument)) throw new IllegalArgumentException();
        if (!Set.of("org.h2.Driver", "eu.kotori.justTeams.libs.h2.Driver").contains(driver) || !url.startsWith("jdbc:h2:file:")) throw new IllegalArgumentException();
        Path root = rootArgument.toRealPath();
        if (network(root) || !Files.isDirectory(root) || !Files.isRegularFile(root.resolve(".justteams-synthetic-fixture"), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
        String[] sections = url.substring("jdbc:h2:file:".length()).split(";", -1);
        Path base = Path.of(sections[0]);
        if (!base.isAbsolute() || network(base) || !base.getParent().toRealPath().startsWith(root)) throw new IllegalArgumentException();
        Path file = Path.of(base + ".mv.db").toRealPath();
        if (!file.startsWith(root) || network(file)) throw new IllegalArgumentException();
        for (int i = 1; i < sections.length; i++) {
            String option = sections[i].toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("MODE=MYSQL", "AUTO_SERVER=FALSE", "DB_CLOSE_ON_EXIT=FALSE", "IFEXISTS=TRUE").contains(option)) throw new IllegalArgumentException();
        }
        return new ColdGate(null, null, null);
    }
    void check() throws Exception {
        if (probe == null) return;
        State state = probe.inspect(container); stopped(state);
        if (!state.startedAt().equals(initial.startedAt())) throw new IllegalArgumentException();
    }
    static State parseState(String json) throws Exception {
        JsonElement parsed = ColdFiles.parse(json);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException();
        JsonObject object = parsed.getAsJsonObject();
        return new State(bool(object, "Running"), bool(object, "Restarting"), bool(object, "Paused"), ColdFiles.text(object, "StartedAt"));
    }
    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException();
        return value.getAsBoolean();
    }
    private static void stopped(State state) {
        if (state == null || state.running() || state.restarting() || state.paused()) throw new IllegalArgumentException();
        Instant.parse(state.startedAt());
    }
    private static State inspectDocker(String container) throws Exception {
        Process process = new ProcessBuilder("docker", "inspect", "--format", "{{json .State}}", container)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var reading = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return process.getInputStream().readNBytes(16 * 1024 + 1); }
            catch (java.io.IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        });
        byte[] data;
        try { data = reading.get(20, TimeUnit.SECONDS); }
        catch (Exception failed) { process.destroyForcibly(); throw new IllegalArgumentException(); }
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalArgumentException(); }
        if (process.exitValue() != 0 || data.length > 16 * 1024) throw new IllegalArgumentException();
        return parseState(new String(data, java.nio.charset.StandardCharsets.UTF_8).trim());
    }
    private static boolean network(Path path) { String text = path.toString(); return text.startsWith("\\\\") || text.startsWith("//"); }
}
