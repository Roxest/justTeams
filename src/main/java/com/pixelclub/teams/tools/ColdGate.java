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
    private static final Set<String> CONTAINERS = Set.of("pixelclub-arclight", "pixelclub-fabric", "pixelclub-arclight-dev");
    record State(boolean running, boolean restarting, boolean paused, String startedAt, String restartPolicy) {}
    interface Probe { State inspect(String container) throws Exception; }
    private final String container;
    private final Probe probe;
    private final State initial;
    private final boolean mutating;

    private ColdGate(String container, Probe probe, State initial, boolean mutating) { this.container = container; this.probe = probe; this.initial = initial; this.mutating = mutating; }
    static ColdGate docker(String container, boolean mutating) throws Exception { return docker(container, mutating, ColdGate::inspectDocker); }
    static ColdGate docker(String container, Probe probe) throws Exception { return docker(container, true, probe); }
    static ColdGate docker(String container, boolean mutating, Probe probe) throws Exception {
        if (!CONTAINERS.contains(container)) throw new IllegalArgumentException();
        State state = probe.inspect(container); stopped(state, mutating);
        return new ColdGate(container, probe, state, mutating);
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
        return new ColdGate(null, null, null, false);
    }
    void check() throws Exception {
        if (probe == null) return;
        State state = probe.inspect(container); stopped(state, mutating);
        if (!state.startedAt().equals(initial.startedAt())) throw new IllegalArgumentException();
    }
    static State parseState(String json) throws Exception {
        JsonElement parsed = ColdFiles.parse(json);
        if (parsed.isJsonArray() && parsed.getAsJsonArray().size() == 1) parsed = parsed.getAsJsonArray().get(0);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException();
        JsonObject record = parsed.getAsJsonObject();
        if (!record.has("State") || !record.get("State").isJsonObject() || !record.has("HostConfig") || !record.get("HostConfig").isJsonObject()) throw new IllegalArgumentException();
        JsonObject state = record.getAsJsonObject("State"), host = record.getAsJsonObject("HostConfig");
        if (!host.has("RestartPolicy") || !host.get("RestartPolicy").isJsonObject()) throw new IllegalArgumentException();
        return new State(bool(state, "Running"), bool(state, "Restarting"), bool(state, "Paused"), ColdFiles.text(state, "StartedAt"), ColdFiles.text(host.getAsJsonObject("RestartPolicy"), "Name"));
    }
    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException();
        return value.getAsBoolean();
    }
    private static void stopped(State state, boolean mutating) {
        if (state == null || state.running() || state.restarting() || state.paused()) throw new IllegalArgumentException();
        if (state.restartPolicy() == null || mutating && !state.restartPolicy().equals("no")) throw new IllegalArgumentException();
        Instant.parse(state.startedAt());
    }
    private static State inspectDocker(String container) throws Exception {
        // Read only the required inspect fields; full inspect also contains private environment data.
        String projection = "{\"State\":{{json .State}},\"HostConfig\":{\"RestartPolicy\":{{json .HostConfig.RestartPolicy}}}}";
        Process process = new ProcessBuilder("docker", "inspect", "--format", projection, container)
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
