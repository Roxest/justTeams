package com.pixelclub.teams.chest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pixelclub.teams.TeamsCore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The journal is a crash log for chest operations still in flight, not a history: PRD reached 305 MB in four days (2026-10-07). */
class ChestJournalTest {
    @TempDir Path temporary;

    private static JsonObject payload(int chars) {
        JsonObject payload = new JsonObject();
        payload.addProperty("generationBefore", 1);
        payload.addProperty("chestBefore", "x".repeat(chars));
        return payload;
    }

    @Test void settledOperationsDoNotAccumulate() throws Exception {
        Path file = temporary.resolve("chest-intents.jsonl");
        ChestJournal journal = new ChestJournal(file);
        journal.load();
        UUID actor = UUID.randomUUID();
        // 150 clicks of ~60 KB each, every one settled in MySQL (DONE): 9 MB, past the 8 MiB the PRD build tolerates.
        for (int i = 0; i < 150; i++) {
            String op = UUID.randomUUID().toString();
            journal.append(op, 7, actor, "PREPARED", payload(20_000));
            journal.append(op, 7, actor, "CHEST_INTENT", payload(20_000));
            journal.append(op, 7, actor, "DONE", payload(20_000));
        }
        assertTrue(Files.size(file) < 8L << 20, "journal holds " + Files.size(file) + " bytes of settled operations");
        assertTrue(journal.load().values().stream().allMatch(head -> head.phase().getAsString().equals("DONE")));
    }

    /** Every phase a pxc.9 build writes, plus one it does not know, interleaved with settled clicks. */
    private record Scenario(Path file, Set<String> unsettled) {}

    private Scenario scenario(String name) throws Exception {
        Path file = temporary.resolve(name);
        ChestJournal writer = new ChestJournal(file);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Set<String> unsettled = new TreeSet<>();
        String frozen = UUID.randomUUID().toString(), escrow = UUID.randomUUID().toString(), inFlight = UUID.randomUUID().toString();
        String cursor = UUID.randomUUID().toString(), unknown = UUID.randomUUID().toString(), claim = UUID.randomUUID().toString();
        unsettled.addAll(List.of(frozen, escrow, inFlight, cursor, unknown, claim));
        for (int i = 0; i < 40; i++) {
            String done = UUID.randomUUID().toString();
            writer.append(done, 3, a, "PREPARED", payload(900));
            if (i == 5) writer.append(frozen, 3, b, "PREPARED", payload(700));
            writer.append(done, 3, a, "CHEST_INTENT", payload(900));
            if (i == 5) writer.append(frozen, 3, b, "CHEST_INTENT", payload(700));
            if (i == 6) writer.append(frozen, 3, b, "FROZEN", payload(700));
            writer.append(done, 3, a, i % 2 == 0 ? "DONE" : "CURSOR_HELD", payload(900));
            if (i % 2 == 1) writer.append(done, 3, a, "DONE", payload(100));
            if (i == 9) {
                for (String phase : List.of("PREPARED", "CHEST_INTENT", "CURSOR_HELD", "CLOSE_INTENT", "ESCROW")) writer.append(escrow, 0, c, phase, payload(500));
                String cancelled = UUID.randomUUID().toString();
                writer.append(cancelled, 4, c, "PREPARED", payload(300));
                writer.append(cancelled, 4, c, "CANCELLED", payload(300));
            }
            if (i == 20) for (String phase : List.of("ESCROW", "CLAIM_INTENT")) writer.append(claim, 4, c, phase, payload(400));
            if (i == 30) writer.append(unknown, 4, c, "FUTURE_PHASE", payload(200));
        }
        writer.append(cursor, 5, b, "PREPARED", payload(600));
        writer.append(cursor, 5, b, "CHEST_INTENT", payload(600));
        writer.append(cursor, 5, b, "CURSOR_HELD", payload(600));
        writer.append(inFlight, 5, a, "PREPARED", payload(600));
        writer.append(inFlight, 5, a, "CHEST_INTENT", payload(600));
        return new Scenario(file, unsettled);
    }

    @Test void bootKeepsEveryRecordOfUnsettledOperationsByteForByte() throws Exception {
        Scenario scenario = scenario("chest-intents.jsonl");
        byte[] original = Files.readAllBytes(scenario.file());
        Map<String, String> before = pxc9Latest(scenario.file());
        Set<String> frozenBefore = pxc9Frozen(scenario.file());

        Map<String, ChestJournal.Head> heads = new ChestJournal(scenario.file()).load();

        assertEquals(before.keySet(), heads.keySet(), "boot still reports every operation it read");
        Path copy = temporary.resolve("original.jsonl");
        Files.write(copy, original);
        assertEquals(keptLines(copy, scenario.unsettled()), new String(Files.readAllBytes(scenario.file()), StandardCharsets.UTF_8));
        Map<String, String> after = pxc9Latest(scenario.file());
        assertEquals(scenario.unsettled(), after.keySet());
        for (String op : scenario.unsettled()) assertEquals(before.get(op), after.get(op));
        assertEquals(frozenBefore, pxc9Frozen(scenario.file()), "a pxc.9 boot (rollback) quarantines exactly the same actors and teams");
        try (var files = Files.list(temporary)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".compact")), "no compaction copy left behind");
        }
    }

    @Test void onlineCompactionKeepsTheFileBoundedAndUnsettledOperationsWhole() throws Exception {
        Path file = temporary.resolve("chest-intents.jsonl");
        long threshold = 64 << 10;
        ChestJournal journal = new ChestJournal(file, threshold);
        journal.load();
        UUID actor = UUID.randomUUID(), other = UUID.randomUUID();
        String escrow = UUID.randomUUID().toString(), held = null;
        for (String phase : List.of("PREPARED", "CHEST_INTENT", "CURSOR_HELD", "CLOSE_INTENT", "ESCROW")) journal.append(escrow, 9, other, phase, payload(3_000));
        long largest = 0;
        for (int i = 0; i < 400; i++) {
            String op = UUID.randomUUID().toString();
            journal.append(op, 9, actor, "PREPARED", payload(2_000));
            journal.append(op, 9, actor, "CHEST_INTENT", payload(4_000));
            // A held cursor stays unsettled until the next click supersedes it, across compactions.
            journal.append(op, 9, actor, i % 3 == 0 ? "CURSOR_HELD" : "DONE", payload(4_000));
            if (held != null) journal.append(held, 9, actor, "DONE", payload(4_000));
            held = i % 3 == 0 ? op : null;
            largest = Math.max(largest, Files.size(file));
            assertEquals(Files.size(file), journal.bytes());
        }
        assertTrue(journal.compactions() > 10, "compactions: " + journal.compactions());
        assertTrue(largest < 2 * threshold + (64 << 10), "largest journal " + largest);
        Map<String, String> latest = pxc9Latest(file);
        assertEquals("ESCROW", latest.get(escrow));
        assertEquals(5, recordsOf(file, escrow), "the escrow operation keeps all five records");
        assertEquals(Set.of(escrow, held), Set.copyOf(latest.entrySet().stream().filter(entry -> !Set.of("DONE", "CANCELLED").contains(entry.getValue())).map(Map.Entry::getKey).toList()));
        assertEquals("CURSOR_HELD", latest.get(held));
    }

    @Test void anOperationUnsettledAfterDoneKeepsItsRecords() throws Exception {
        UUID actor = UUID.randomUUID();
        // threshold 10 KB: no compaction between DONE and FROZEN, so all five records are still there to keep.
        // threshold 1: DONE itself triggers one, and the FROZEN record (TeamChests puts the whole payload in it) stands alone.
        for (long threshold : new long[] {10 << 10, 1}) {
            Path file = temporary.resolve("chest-intents-" + threshold + ".jsonl");
            ChestJournal journal = new ChestJournal(file, threshold);
            journal.load();
            String op = UUID.randomUUID().toString();
            journal.append(op, 2, actor, "PREPARED", payload(100));
            journal.append(op, 2, actor, "CHEST_INTENT", payload(100));
            journal.append(op, 2, actor, "CURSOR_HELD", payload(100));
            // TeamChests.transaction: DONE lands, then a failed previousOp update quarantines the same operation.
            journal.append(op, 2, actor, "DONE", payload(100));
            journal.append(op, 2, actor, "FROZEN", payload(100));
            String settled = UUID.randomUUID().toString();
            journal.append(settled, 2, actor, "PREPARED", payload(6_000));
            journal.append(settled, 2, actor, "DONE", payload(6_000));
            assertTrue(journal.compactions() > 0);
            assertEquals(threshold == 1 ? 1 : 5, recordsOf(file, op));
            assertEquals(0, recordsOf(file, settled));
            assertEquals("FROZEN", new ChestJournal(file).latestRecord(op).get("phase").getAsString());
            assertEquals(Set.of("player:" + actor, "team:2"), pxc9Frozen(file));
        }
    }

    @Test void anIdentityChangeIsKeptSoBootStillRefusesIt() throws Exception {
        Path file = temporary.resolve("chest-intents.jsonl");
        ChestJournal journal = new ChestJournal(file, 1);
        journal.load();
        UUID actor = UUID.randomUUID();
        String op = UUID.randomUUID().toString();
        journal.append(op, 2, actor, "PREPARED", payload(100));
        journal.append(op, 3, actor, "DONE", payload(100));
        String settled = UUID.randomUUID().toString();
        journal.append(settled, 2, actor, "PREPARED", payload(5_000));
        journal.append(settled, 2, actor, "DONE", payload(5_000));
        assertTrue(journal.compactions() > 0);
        TeamsCore.Refusal refusal = assertThrows(TeamsCore.Refusal.class, () -> new ChestJournal(file).load());
        assertEquals("CHEST_JOURNAL_IDENTITY_CHANGED", refusal.getMessage());
    }

    @Test void aCompactionThatNeverRenamedIsDiscardedAtBoot() throws Exception {
        Scenario scenario = scenario("chest-intents.jsonl");
        Path stray = temporary.resolve("chest-intents.jsonl.compact");
        Files.writeString(stray, "{\"opid\":\"half a compaction");
        Set<String> frozen = pxc9Frozen(scenario.file());
        new ChestJournal(scenario.file()).load();
        assertFalse(Files.exists(stray));
        assertEquals(frozen, pxc9Frozen(scenario.file()));
        assertEquals(scenario.unsettled(), pxc9Latest(scenario.file()).keySet());
    }

    @Test void aFailedCompactionLeavesTheJournalAsItWas() throws Exception {
        Scenario scenario = scenario("chest-intents.jsonl");
        byte[] original = Files.readAllBytes(scenario.file());
        // A directory where the compacted copy would go: it can neither be removed nor written.
        Files.createDirectories(temporary.resolve("chest-intents.jsonl.compact"));
        Files.writeString(temporary.resolve("chest-intents.jsonl.compact").resolve("pin"), "x");
        ChestJournal journal = new ChestJournal(scenario.file(), 1);
        Map<String, ChestJournal.Head> heads = journal.load();
        assertArrayEquals(original, Files.readAllBytes(scenario.file()));
        assertEquals(pxc9Latest(scenario.file()).keySet(), heads.keySet());
        UUID actor = UUID.randomUUID();
        String op = UUID.randomUUID().toString();
        journal.append(op, 1, actor, "PREPARED", payload(100));
        journal.append(op, 1, actor, "DONE", payload(100));
        assertEquals(0, journal.compactions());
        assertEquals("DONE", pxc9Latest(scenario.file()).get(op));
    }

    @Test void anUnterminatedLastRecordGetsItsSeparatorBack() throws Exception {
        Path file = temporary.resolve("chest-intents.jsonl");
        ChestJournal writer = new ChestJournal(file);
        UUID actor = UUID.randomUUID();
        String held = UUID.randomUUID().toString();
        writer.append(held, 1, actor, "PREPARED", payload(100));
        writer.append(held, 1, actor, "CURSOR_HELD", payload(100));
        byte[] bytes = Files.readAllBytes(file);
        int cut = bytes.length - System.lineSeparator().length();
        Files.write(file, java.util.Arrays.copyOf(bytes, cut));
        ChestJournal journal = new ChestJournal(file);
        journal.load();
        assertTrue(Files.readString(file).endsWith(System.lineSeparator()));
        journal.append(held, 1, actor, "DONE", payload(100));
        assertEquals("DONE", pxc9Latest(file).get(held));
    }

    @Test void aTornLastRecordStillRefusesBootAndIsLeftAlone() throws Exception {
        Path file = temporary.resolve("chest-intents.jsonl");
        ChestJournal writer = new ChestJournal(file);
        writer.append(UUID.randomUUID().toString(), 1, UUID.randomUUID(), "DONE", payload(100));
        Files.writeString(file, "{\"opid\":\"torn", StandardOpenOption.APPEND);
        byte[] original = Files.readAllBytes(file);
        assertThrows(RuntimeException.class, () -> new ChestJournal(file).load());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void recordsKeepThePxc9Layout() throws Exception {
        Path file = temporary.resolve("chest-intents.jsonl");
        UUID actor = UUID.fromString("4fdca6c4-a22b-3394-bf43-cc9e3b70b011");
        new ChestJournal(file).append("144e0a38-fde0-4b8c-98b0-4d6d1d6d885e", 254, actor, "DONE", payload(3));
        String line = Files.readString(file);
        assertTrue(line.matches("\\{\"opid\":\"144e0a38-fde0-4b8c-98b0-4d6d1d6d885e\",\"team\":254,\"player\":\"4fdca6c4-a22b-3394-bf43-cc9e3b70b011\",\"phase\":\"DONE\",\"at\":\\d+,"
                + "\"payload\":\\{\"generationBefore\":1,\"chestBefore\":\"xxx\"}}" + System.lineSeparator()), line);
    }

    /**
     * Rehearsal on a copy of a real journal (PXC_JOURNAL_COPY=<file>, never the original): pxc.9's boot decisions before
     * and after, kept records byte for byte, and the archived original restoring to the same SHA-256.
     */
    @Test @EnabledIfEnvironmentVariable(named = "PXC_JOURNAL_COPY", matches = ".+")
    void rehearsalOnARealJournalCopy() throws Exception {
        Path source = Path.of(System.getenv("PXC_JOURNAL_COPY"));
        Path file = temporary.resolve("chest-intents.jsonl");
        Files.copy(source, file);
        String originalSha = sha(file);
        Path archive = temporary.resolve("chest-intents.jsonl.gz");
        try (InputStream in = Files.newInputStream(file); OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive))) { in.transferTo(out); }

        long started = System.nanoTime();
        Map<String, String> before = pxc9Latest(file);
        long pxc9Millis = (System.nanoTime() - started) / 1_000_000;
        Set<String> frozenBefore = pxc9Frozen(file);
        Set<String> unsettled = new TreeSet<>();
        before.forEach((op, phase) -> { if (!Set.of("DONE", "CANCELLED").contains(phase)) unsettled.add(op); });
        String expected = keptLines(file, unsettled);
        long bytesBefore = Files.size(file);

        started = System.nanoTime();
        new ChestJournal(file).load();
        long loadMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(expected, Files.readString(file), "kept records are the original bytes");
        assertEquals(frozenBefore, pxc9Frozen(file));
        Map<String, String> after = pxc9Latest(file);
        assertEquals(unsettled, after.keySet());
        for (String op : unsettled) assertEquals(before.get(op), after.get(op));
        long bytesAfter = Files.size(file);
        long startedAgain = System.nanoTime();
        new ChestJournal(file).load();
        long secondLoadMillis = (System.nanoTime() - startedAgain) / 1_000_000;
        assertEquals(bytesAfter, Files.size(file), "a second boot has nothing to drop");

        Path restored = temporary.resolve("restored.jsonl");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archive))) { Files.copy(in, restored); }
        assertEquals(originalSha, sha(restored), "the archive restores the exact original");
        Map<String, Long> phases = new LinkedHashMap<>();
        after.values().forEach(phase -> phases.merge(phase, 1L, Long::sum));
        System.out.printf("REHEARSAL source=%s sha256=%s bytes %d -> %d, operations %d -> %d (unsettled phases %s), frozen-before=%d frozen-after=%d, pxc.9 read %d ms, pxc.10 boot (read+compact) %d ms, second boot %d ms, archive.gz %d bytes%n",
                source.getFileName(), originalSha, bytesBefore, bytesAfter, before.size(), after.size(), phases, frozenBefore.size(), pxc9Frozen(file).size(), pxc9Millis, loadMillis, secondLoadMillis, Files.size(archive));
    }

    /** The original lines of the kept operations, separators included, in file order; streamed. */
    private static String keptLines(Path journal, Set<String> kept) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), line = new ByteArrayOutputStream();
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(journal), 1 << 16)) {
            for (int b = in.read(); ; b = in.read()) {
                if (b >= 0) line.write(b);
                if (b == '\n' || b < 0 && line.size() > 0) {
                    String text = line.toString(StandardCharsets.UTF_8);
                    if (b < 0) line.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
                    if (!text.isBlank() && kept.contains(JsonParser.parseString(text.trim()).getAsJsonObject().get("opid").getAsString())) line.writeTo(out);
                    line.reset();
                }
                if (b < 0) break;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static long recordsOf(Path file, String opid) throws IOException {
        try (var lines = Files.lines(file)) {
            return lines.filter(line -> !line.isBlank()).filter(line -> JsonParser.parseString(line).getAsJsonObject().get("opid").getAsString().equals(opid)).count();
        }
    }

    /** pxc.9's TeamChests.restoreQuarantine read, as the reference: latest phase of every operation. */
    private static Map<String, String> pxc9Latest(Path file) throws IOException {
        Map<String, String> latest = new LinkedHashMap<>();
        if (!Files.exists(file)) return latest;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                latest.put(record.get("opid").getAsString(), record.get("phase").getAsString());
            }
        }
        return latest;
    }

    /** pxc.9's quarantine decision: actors and teams of operations whose latest phase is not DONE, CANCELLED or ESCROW. */
    private static Set<String> pxc9Frozen(Path file) throws IOException {
        Map<String, JsonObject> latest = new LinkedHashMap<>();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject record = JsonParser.parseString(line).getAsJsonObject(), head = new JsonObject();
                for (String key : List.of("player", "team", "phase")) head.add(key, record.get(key));
                latest.put(record.get("opid").getAsString(), head);
            }
        }
        Set<String> frozen = new TreeSet<>();
        for (JsonObject record : latest.values()) {
            if (Set.of("DONE", "CANCELLED", "ESCROW").contains(record.get("phase").getAsString())) continue;
            frozen.add("player:" + UUID.fromString(record.get("player").getAsString()));
            if (record.get("team").getAsInt() > 0) frozen.add("team:" + record.get("team").getAsInt());
        }
        return frozen;
    }

    private static String sha(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] chunk = new byte[1 << 16];
            for (int n; (n = in.read(chunk)) > 0; ) digest.update(chunk, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
