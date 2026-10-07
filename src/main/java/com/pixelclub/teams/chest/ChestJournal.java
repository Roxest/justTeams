package com.pixelclub.teams.chest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pixelclub.teams.TeamsCore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The team-chest crash journal ({@code config/JustTeams/chest-intents.jsonl}): one JSON line per phase of every chest
 * operation, forced to disk before the step it announces. Boot reads the latest record of each operation to find the
 * operations a crash interrupted; operator recovery reads the latest record of one FROZEN operation.
 */
public final class ChestJournal {
    /** Identity and phase of an operation's latest record, as boot reads them. */
    public record Head(String opid, JsonElement player, JsonElement team, JsonElement phase) {}

    private final Path file;

    public ChestJournal(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized void append(String opid, int team, UUID player, String phase, JsonObject payload) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        JsonObject record = new JsonObject();
        record.addProperty("opid", opid);
        record.addProperty("team", team);
        record.addProperty("player", player.toString());
        record.addProperty("phase", phase);
        record.addProperty("at", System.currentTimeMillis());
        record.add("payload", payload.deepCopy());
        ByteBuffer bytes = ByteBuffer.wrap((record + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }

    /** Latest head of every operation, in first-seen order. A record without identity, or an operation whose actor or team changes, is refused. */
    public synchronized Map<String, Head> load() throws IOException {
        Map<String, Head> latest = new LinkedHashMap<>();
        if (!Files.exists(file)) return latest;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                Head head = head(JsonParser.parseString(line).getAsJsonObject());
                Head previous = latest.get(head.opid());
                if (previous != null && (!previous.player().equals(head.player()) || !previous.team().equals(head.team()))) throw new TeamsCore.Refusal("CHEST_JOURNAL_IDENTITY_CHANGED");
                latest.put(head.opid(), head);
            }
        }
        return latest;
    }

    /** The full latest record of one operation, or null. */
    public synchronized JsonObject latestRecord(String opid) throws IOException {
        if (!Files.exists(file)) return null;
        JsonObject latest = null;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                if (record.has("opid") && record.get("opid").getAsString().equals(opid)) latest = record;
            }
        }
        return latest;
    }

    /** The full latest record of every operation, in first-seen order. */
    public synchronized Collection<JsonObject> latestRecords() throws IOException {
        Map<String, JsonObject> latest = new LinkedHashMap<>();
        if (!Files.exists(file)) return new ArrayList<>();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                latest.put(record.get("opid").getAsString(), record);
            }
        }
        return latest.values();
    }

    private static Head head(JsonObject record) {
        for (String key : List.of("opid", "player", "team", "phase")) if (!record.has(key) || record.get(key).isJsonNull()) throw new TeamsCore.Refusal("CHEST_JOURNAL_INVALID");
        return new Head(record.get("opid").getAsString(), record.get("player"), record.get("team"), record.get("phase"));
    }
}
