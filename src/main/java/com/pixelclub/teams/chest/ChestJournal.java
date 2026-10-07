package com.pixelclub.teams.chest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.pixelclub.teams.TeamsCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The team-chest crash journal ({@code config/JustTeams/chest-intents.jsonl}): one JSON line per phase of every chest
 * operation, forced to disk before the step it announces. Boot reads the latest record of each operation to find the
 * operations a crash interrupted; operator recovery reads the latest record of one FROZEN operation.
 *
 * <p>pxc.10: the file holds only operations that can still matter. An operation whose latest record is DONE or CANCELLED
 * is settled: that record follows the matching MySQL update, and neither reader tells a settled operation apart from one
 * that was never journalled (boot quarantines only the other phases, recovery accepts only FROZEN). Settled operations are
 * dropped at boot, and while the server runs once their bytes reach {@code compactAfterBytes} and the unsettled bytes.
 * Every other operation (ESCROW, FROZEN, anything in flight, phases this build does not know, and any operation whose actor
 * or team changed, which boot refuses) keeps all of its records byte for byte, in file order. A compaction writes a sibling
 * file, forces it, reads it back, and only then renames it over the journal. The audit trail of a settled operation is its
 * row in {@code pxc_teams_operations}. PRD's file reached 305 MB in four days without this (2026-10-07).
 */
public final class ChestJournal {
    public static final long DEFAULT_COMPACT_AFTER_BYTES = 8L << 20;
    private static final Set<String> SETTLED = Set.of("DONE", "CANCELLED");
    private static final Logger LOG = LoggerFactory.getLogger("justteams");

    /** Identity and phase of an operation's latest record, as boot reads them. */
    public record Head(String opid, JsonElement player, JsonElement team, JsonElement phase) {}

    /** One record's bytes in the file; phase is its JSON text, checked when a compaction reads the record back. */
    private record Range(long offset, int length, String phase) {}

    private static final class Operation {
        final List<Range> records = new ArrayList<>();
        long bytes;
        String phase, player, team;
        boolean identityChanged;

        boolean kept() {
            return phase != null && (identityChanged || !SETTLED.contains(phase));
        }
    }

    private final Path file, temporary;
    private final long compactAfterBytes;
    /** Every operation with a record in the file, while {@link #tracked}. */
    private final Map<String, Operation> operations = new LinkedHashMap<>();
    /** The ranges below describe the whole file: set by a complete read, cleared by anything that could make them wrong. */
    private boolean tracked;
    private long fileBytes, keptBytes, retryAt;
    private int compactions;

    public ChestJournal(Path file) {
        this(file, DEFAULT_COMPACT_AFTER_BYTES);
    }

    public ChestJournal(Path file, long compactAfterBytes) {
        this.file = file;
        this.temporary = file.resolveSibling(file.getFileName() + ".compact");
        this.compactAfterBytes = Math.max(1, compactAfterBytes);
    }

    public Path file() {
        return file;
    }

    /** Journal size this server last wrote or read; -1 when it no longer tracks the file. */
    public synchronized long bytes() {
        return tracked ? fileBytes : -1;
    }

    public synchronized int compactions() {
        return compactions;
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
        byte[] line = (record + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        long offset;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            offset = channel.size();
            ByteBuffer bytes = ByteBuffer.wrap(line);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (IOException | RuntimeException error) {
            // Part of this record may be in the file now; only the next boot's full read describes it again.
            tracked = false;
            throw error;
        }
        if (!tracked) return;
        if (offset != fileBytes) {
            tracked = false;
            LOG.warn("[pxc-teams] chest journal changed outside this server ({} bytes expected, {} found): no compaction until the next boot", fileBytes, offset);
            return;
        }
        fileBytes = offset + line.length;
        record(opid, new JsonPrimitive(player.toString()).toString(), new JsonPrimitive(team).toString(), phase, new Range(offset, line.length, new JsonPrimitive(phase).toString()));
        if (fileBytes - keptBytes >= Math.max(compactAfterBytes, keptBytes) && fileBytes >= retryAt) {
            // The record above is durable; a failed compaction leaves the journal as it was and must not fail the click.
            try {
                compact("online");
            } catch (IOException | RuntimeException error) {
                retryAt = fileBytes + compactAfterBytes;
                LOG.warn("[pxc-teams] chest journal compaction failed, journal unchanged ({} bytes): {}", fileBytes, error.toString());
            }
        }
    }

    /**
     * Latest head of every operation, in first-seen order. A record without identity, or an operation whose actor or team
     * changes, is refused. Settled operations are then dropped from the file; a failed compaction leaves it as it was.
     */
    public synchronized Map<String, Head> load() throws IOException {
        operations.clear();
        tracked = false;
        fileBytes = keptBytes = retryAt = 0;
        // A compaction that never reached its rename: the journal itself is whole.
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException error) {
            LOG.warn("[pxc-teams] chest journal: leftover {} not removed ({}); compactions fail until it is", temporary.getFileName(), error.toString());
        }
        Map<String, Head> latest = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            tracked = true;
            return latest;
        }
        long started = System.nanoTime();
        int records = 0;
        boolean terminated = true;
        try (Lines lines = new Lines(file)) {
            for (Lines.Line line; (line = lines.next()) != null; ) {
                fileBytes = line.offset() + line.length();
                terminated = line.terminated();
                if (line.text().isBlank()) continue;
                JsonObject record = JsonParser.parseString(line.text()).getAsJsonObject();
                Head head = head(record);
                Head previous = latest.get(head.opid());
                if (previous != null && (!previous.player().equals(head.player()) || !previous.team().equals(head.team()))) throw new TeamsCore.Refusal("CHEST_JOURNAL_IDENTITY_CHANGED");
                latest.put(head.opid(), head);
                Operation operation = operations.computeIfAbsent(head.opid(), key -> new Operation());
                operation.records.add(new Range(line.offset(), line.length(), head.phase().toString()));
                operation.bytes += line.length();
                records++;
            }
        }
        for (Head head : latest.values()) {
            Operation operation = operations.get(head.opid());
            operation.phase = head.phase().getAsString();
            operation.player = head.player().toString();
            operation.team = head.team().toString();
            if (operation.kept()) keptBytes += operation.bytes;
        }
        tracked = true;
        String read = records + " records of " + operations.size() + " operation(s), " + fileBytes + " bytes, read in " + (System.nanoTime() - started) / 1_000_000 + " ms";
        if (fileBytes > keptBytes || !terminated) {
            try {
                compact("boot: " + read);
            } catch (IOException | RuntimeException error) {
                retryAt = fileBytes + compactAfterBytes;
                LOG.warn("[pxc-teams] chest journal ({}) not compacted, journal unchanged: {}", read, error.toString());
            }
        } else {
            LOG.info("[pxc-teams] chest journal: {}; nothing settled to drop", read);
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

    private void record(String opid, String player, String team, String phase, Range range) {
        Operation operation = operations.computeIfAbsent(opid, key -> new Operation());
        if (operation.kept()) keptBytes -= operation.bytes;
        if (operation.records.isEmpty()) {
            operation.player = player;
            operation.team = team;
        } else if (!operation.player.equals(player) || !operation.team.equals(team)) {
            operation.identityChanged = true;
        }
        operation.records.add(range);
        operation.bytes += range.length();
        operation.phase = phase;
        if (operation.kept()) keptBytes += operation.bytes;
    }

    /** Rewrites the journal with the kept operations' records only, in file order; the journal is replaced only by a verified copy. */
    private void compact(String reason) throws IOException {
        long started = System.nanoTime();
        long size = Files.size(file);
        if (size != fileBytes) {
            tracked = false;
            throw new IOException("journal changed outside this server: " + fileBytes + " bytes expected, " + size + " found");
        }
        Map<Long, String> owners = new HashMap<>();
        List<Range> ranges = new ArrayList<>();
        int dropped = 0;
        for (Map.Entry<String, Operation> entry : operations.entrySet()) {
            if (!entry.getValue().kept()) {
                dropped++;
                continue;
            }
            for (Range range : entry.getValue().records) {
                ranges.add(range);
                owners.put(range.offset(), entry.getKey());
            }
        }
        ranges.sort(Comparator.comparingLong(Range::offset));
        Map<String, List<Range>> moved = new LinkedHashMap<>();
        byte[] separator = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
        MessageDigest written = digest();
        long position = 0;
        try (FileChannel source = FileChannel.open(file, StandardOpenOption.READ);
             FileChannel target = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (Range range : ranges) {
                ByteBuffer bytes = ByteBuffer.allocate(range.length());
                while (bytes.hasRemaining()) if (source.read(bytes, range.offset() + bytes.position()) < 0) throw new IOException("journal ended inside a record at " + range.offset());
                byte[] line = bytes.array();
                // Only the last line of a file can lack its separator; the next append must start on a new line.
                if (line[line.length - 1] != '\n') {
                    line = Arrays.copyOf(line, line.length + separator.length);
                    System.arraycopy(separator, 0, line, range.length(), separator.length);
                }
                ByteBuffer out = ByteBuffer.wrap(line);
                while (out.hasRemaining()) target.write(out);
                written.update(line);
                moved.computeIfAbsent(owners.get(range.offset()), key -> new ArrayList<>()).add(new Range(position, line.length, range.phase()));
                position += line.length;
            }
            target.force(true);
        } catch (IOException | RuntimeException error) {
            discard(error);
            throw error;
        }
        try {
            verify(position, written.digest(), ranges, owners);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException error) {
            discard(error);
            throw error;
        }
        forceDirectory();
        long before = fileBytes;
        operations.keySet().retainAll(moved.keySet());
        for (Map.Entry<String, List<Range>> entry : moved.entrySet()) {
            Operation operation = operations.get(entry.getKey());
            operation.records.clear();
            operation.records.addAll(entry.getValue());
            operation.bytes = entry.getValue().stream().mapToLong(Range::length).sum();
        }
        fileBytes = keptBytes = position;
        retryAt = 0;
        compactions++;
        LOG.info("[pxc-teams] chest journal compacted ({}): {} -> {} bytes, kept {} records of {} unsettled operation(s), dropped {} settled, {} ms",
                reason, before, position, ranges.size(), moved.size(), dropped, (System.nanoTime() - started) / 1_000_000);
    }

    /** The copy must hold exactly the bytes written: one parseable record per kept range, of the operation and phase it was copied for. */
    private void verify(long expectedBytes, byte[] expectedDigest, List<Range> ranges, Map<Long, String> owners) throws IOException {
        if (Files.size(temporary) != expectedBytes) throw new IOException("compacted journal has " + Files.size(temporary) + " bytes, " + expectedBytes + " written");
        int index = 0;
        try (Lines lines = new Lines(temporary)) {
            for (Lines.Line line; (line = lines.next()) != null; index++) {
                if (index >= ranges.size()) throw new IOException("compacted journal has more records than were kept");
                Range range = ranges.get(index);
                Head head = head(JsonParser.parseString(line.text()).getAsJsonObject());
                if (!head.opid().equals(owners.get(range.offset())) || !head.phase().toString().equals(range.phase())) throw new IOException("compacted record " + index + " is " + head.opid() + " " + head.phase() + ", expected " + owners.get(range.offset()) + " " + range.phase());
            }
        }
        if (index != ranges.size()) throw new IOException("compacted journal has " + index + " records, " + ranges.size() + " kept");
        MessageDigest read = digest();
        try (InputStream in = Files.newInputStream(temporary)) {
            byte[] chunk = new byte[1 << 16];
            for (int n; (n = in.read(chunk)) > 0; ) read.update(chunk, 0, n);
        }
        if (!MessageDigest.isEqual(expectedDigest, read.digest())) throw new IOException("compacted journal does not read back as written");
    }

    private void discard(Exception error) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanup) {
            error.addSuppressed(cleanup);
        }
    }

    private void forceDirectory() {
        // Makes the rename durable on Linux; Windows cannot open a directory for this and its rename is already durable.
        try (FileChannel directory = FileChannel.open(file.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        } catch (IOException ignored) {
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Head head(JsonObject record) {
        for (String key : List.of("opid", "player", "team", "phase")) if (!record.has(key) || record.get(key).isJsonNull()) throw new TeamsCore.Refusal("CHEST_JOURNAL_INVALID");
        return new Head(record.get("opid").getAsString(), record.get("player"), record.get("team"), record.get("phase"));
    }

    /** Lines with their byte ranges. Text drops "\n" or "\r\n" and is decoded strictly, as the BufferedReader reads were. */
    private static final class Lines implements Closeable {
        record Line(long offset, int length, String text, boolean terminated) {}

        private final InputStream in;
        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        private final byte[] chunk = new byte[1 << 16];
        private byte[] line = new byte[1 << 16];
        private int start, end;
        private long position;

        Lines(Path file) throws IOException {
            in = Files.newInputStream(file);
        }

        Line next() throws IOException {
            long offset = position;
            int length = 0;
            boolean terminated = false;
            while (!terminated) {
                if (start == end) {
                    int read = in.read(chunk);
                    if (read <= 0) break;
                    start = 0;
                    end = read;
                }
                int stop = start;
                while (stop < end && chunk[stop] != '\n') stop++;
                terminated = stop < end;
                int take = (terminated ? stop + 1 : end) - start;
                if (length + take > line.length) line = Arrays.copyOf(line, Math.max(line.length * 2, length + take));
                System.arraycopy(chunk, start, line, length, take);
                length += take;
                start += take;
                position += take;
            }
            if (length == 0) return null;
            int text = terminated ? length - 1 : length;
            if (terminated && text > 0 && line[text - 1] == '\r') text--;
            return new Line(offset, length, decoder.decode(ByteBuffer.wrap(line, 0, text)).toString(), terminated);
        }

        @Override public void close() throws IOException {
            in.close();
        }
    }
}
