package com.pixelclub.teams.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.pixelclub.teams.store.ConnectionFactory;
import com.pixelclub.teams.store.TeamStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Only a marked temporary H2 file; no Docker, server, live SQL, account or provider invocation. */
class ColdChestsTest {
    @TempDir Path root;
    private ConnectionFactory factory;
    private TeamStore store;
    private Properties properties;
    private Path config;
    private UUID owner;
    private ByteArrayOutputStream stdout;
    private ByteArrayOutputStream stderr;

    @BeforeEach void setup() throws Exception {
        Files.writeString(root.resolve(".justteams-synthetic-fixture"), "synthetic");
        String url = "jdbc:h2:file:" + root.resolve("teams").toString().replace('\\', '/') + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";
        factory = () -> DriverManager.getConnection(url, "sa", ""); store = new TeamStore(factory, "donut_");
        try (Connection connection = factory.open(); var statement = connection.createStatement()) {
            for (var table : store.requiredSchema().entrySet()) statement.execute("CREATE TABLE `" + table.getKey() + "` (" + table.getValue() + ")");
        }
        store.provisionAdditiveTables(); owner = UUID.randomUUID();
        assertEquals(1, store.create("Synthetic", "SY", owner, "ToolOwner", "fixture").id());
        properties = new Properties(); properties.setProperty("jdbc_url", url); properties.setProperty("username", "sa"); properties.setProperty("password", ""); properties.setProperty("driver", "org.h2.Driver"); properties.setProperty("prefix", "donut_");
        config = root.resolve("private.properties"); saveConfig();
    }

    @Test void importReplayKeepsOriginalAndGenerationAndPrintsProofOnly() throws Exception {
        donor(1, blob("\r\n")); Path[] input = forward(Map.of(1, blob("\r\n")));
        assertEquals(0, run("import", input[0], input[1], root.resolve("proof.json"), true));
        var imported = store.chest(1).orElseThrow();
        assertEquals(1, imported.generation()); assertEquals(blob("\r\n"), original(1));
        assertFalse(stdout.toString(StandardCharsets.UTF_8).contains("inventory_json"));
        assertEquals(0, run("import", input[0], input[1], root.resolve("replay.json"), true));
        assertEquals(imported, store.chest(1).orElseThrow());
    }
    @Test void importRequiresApplyColdSnapshotExactFileBindingAndNewOutput() throws Exception {
        donor(1, blob("\n")); Path[] input = forward(Map.of(1, blob("\n")));
        assertEquals(1, run("import", input[0], input[1], root.resolve("no-apply.json"), false));
        JsonObject snapshot = ColdFiles.read(input[0]).json(); snapshot.addProperty("cold_verified", false); write(input[0], snapshot);
        assertEquals(1, run("import", input[0], input[1], root.resolve("not-cold.json"), true));
        assertTrue(store.chest(1).isEmpty());
        input = forward(Map.of(1, blob("\n"))); Files.writeString(root.resolve("existing.json"), "retain");
        assertEquals(1, run("import", input[0], input[1], root.resolve("existing.json"), true));
        assertEquals("retain", Files.readString(root.resolve("existing.json")));
        JsonObject artifact = ColdFiles.read(input[1]).json(); artifact.addProperty("source_file_sha256", "0".repeat(64)); write(input[1], artifact);
        assertEquals(1, run("import", input[0], input[1], root.resolve("binding.json"), true));
        assertTrue(store.chest(1).isEmpty());
    }
    @Test void importRejectsOmittedCurrentRowsHashDriftAndInvalidCodecShape() throws Exception {
        donor(1, blob("\n")); int second = newTeam("Other", "OT"); donor(second, blob("\r\n"));
        Path[] input = forward(Map.of(1, blob("\n")));
        assertEquals(1, run("import", input[0], input[1], root.resolve("omitted.json"), true));
        input = forward(Map.of(1, blob("\n"), second, blob("\r\n")));
        sql("UPDATE donut_team_enderchest SET inventory_data='drift' WHERE team_id=" + second);
        assertEquals(1, run("import", input[0], input[1], root.resolve("drift.json"), true));
        sql("UPDATE donut_team_enderchest SET inventory_data=NULL WHERE team_id=" + second);
        input = forward(Map.of(1, blob("\n")));
        JsonObject artifact = ColdFiles.read(input[1]).json(); artifact.getAsJsonArray("rows").get(0).getAsJsonObject().addProperty("inventory_json", "{\"format\":1,\"size\":9,\"slots\":[],\"sha256\":\"" + "0".repeat(64) + "\"}"); write(input[1], artifact);
        assertEquals(1, run("import", input[0], input[1], root.resolve("shape.json"), true));
        assertTrue(store.chest(1).isEmpty()); assertTrue(store.chest(second).isEmpty());
    }
    @Test void importSqlFailureRollsBackEveryAddonRow() throws Exception {
        int second = newTeam("Other", "OT"); donor(1, blob("\n")); donor(second, blob("\r\n"));
        Path[] input = forward(Map.of(1, blob("\n"), second, blob("\r\n")));
        sql("ALTER TABLE pxc_teams_chests ADD CONSTRAINT fixture_insert CHECK(team_id <> " + second + ")");
        assertEquals(1, run("import", input[0], input[1], root.resolve("failed.json"), true));
        assertTrue(store.chest(1).isEmpty()); assertTrue(store.chest(second).isEmpty());
        assertEquals(blob("\n"), original(1));
    }
    @Test void rollbackRestoresConvertedAndNewNativeRowsAndRetainsCompletedJournal() throws Exception {
        donor(1, blob("\r\n")); Path[] input = forward(Map.of(1, blob("\r\n")));
        assertEquals(0, run("import", input[0], input[1], root.resolve("import.json"), true));
        int second = newTeam("Other", "OT"); assertTrue(store.saveChest(second, 1, inventory(), null, 0));
        store.beginOperation("complete", 1, owner, "CHEST", 0, "DONE", "{}");
        Path nativeExport = root.resolve("native.json"); assertEquals(0, run("export-native", null, null, nativeExport, false));
        assertTrue(ColdFiles.read(nativeExport).json().get("cold_verified").getAsBoolean());
        Path reverse = reverse(nativeExport);
        assertEquals(0, run("rollback", nativeExport, reverse, root.resolve("rollback.json"), true));
        assertEquals(blob("\n"), original(1)); assertEquals(blob("\n"), original(second));
        assertTrue(store.chest(1).isEmpty()); assertTrue(store.chest(second).isEmpty());
        assertEquals("DONE", store.operation("complete").orElseThrow().phase());
        assertEquals(1, run("rollback", nativeExport, reverse, root.resolve("again.json"), true));
    }
    @Test void rollbackRejectsNativeGenerationDriftSourceHashDriftAndReverseBlobHashDrift() throws Exception {
        donor(1, blob("\r\n")); Path[] input = forward(Map.of(1, blob("\r\n")));
        assertEquals(0, run("import", input[0], input[1], root.resolve("import.json"), true));
        Path snapshot = root.resolve("native.json"); assertEquals(0, run("export-native", null, null, snapshot, false)); Path artifact = reverse(snapshot);
        assertTrue(store.saveChest(1, 1, inventory(), ColdFiles.sha(blob("\r\n")), 1));
        assertEquals(1, run("rollback", snapshot, artifact, root.resolve("gen.json"), true));
        snapshot = root.resolve("latest.json"); assertEquals(0, run("export-native", null, null, snapshot, false)); artifact = reverse(snapshot);
        sql("UPDATE donut_team_enderchest SET inventory_data='changed' WHERE team_id=1");
        assertEquals(1, run("rollback", snapshot, artifact, root.resolve("source.json"), true));
        assertEquals(2, store.chest(1).orElseThrow().generation()); assertEquals("changed", original(1));
        JsonObject malformed = ColdFiles.read(artifact).json(); malformed.getAsJsonArray("rows").get(0).getAsJsonObject().addProperty("reverse_sha256", "0".repeat(64)); write(artifact, malformed);
        assertEquals(1, run("rollback", snapshot, artifact, root.resolve("bad-hash.json"), true));
    }
    @Test void rollbackFailureRevertsEarlierDonorUpdateAndAddonDeletion() throws Exception {
        donor(1, blob("\r\n")); Path[] input = forward(Map.of(1, blob("\r\n")));
        assertEquals(0, run("import", input[0], input[1], root.resolve("import.json"), true));
        int second = newTeam("Other", "OT"); assertTrue(store.saveChest(second, 1, inventory(), null, 0));
        Path snapshot = root.resolve("native.json"); assertEquals(0, run("export-native", null, null, snapshot, false)); Path artifact = reverse(snapshot);
        sql("ALTER TABLE donut_team_enderchest ADD CONSTRAINT fixture_restore CHECK(team_id <> " + second + ")");
        assertEquals(1, run("rollback", snapshot, artifact, root.resolve("rollback.json"), true));
        assertEquals(blob("\r\n"), original(1)); assertNull(original(second));
        assertTrue(store.chest(1).isPresent()); assertTrue(store.chest(second).isPresent());
    }
    @Test void unresolvedBankAndNormalChestEscrowRefuseAllColdModes() throws Exception {
        donor(1, blob("\n")); Path[] input = forward(Map.of(1, blob("\n")));
        assertEquals(0, run("import", input[0], input[1], root.resolve("import.json"), true));
        Path snapshot = root.resolve("native.json"); assertEquals(0, run("export-native", null, null, snapshot, false)); Path artifact = reverse(snapshot);
        store.beginOperation("escrow", 1, owner, "CHEST", 0, "ESCROW", "{}");
        assertEquals(1, run("export-native", null, null, root.resolve("escrow-export.json"), false));
        assertEquals(1, run("rollback", snapshot, artifact, root.resolve("escrow-rollback.json"), true));
        store.updateOperation("escrow", "DONE", "{}"); store.beginOperation("bank", 1, owner, "DEPOSIT", 1, "PREPARED", "{}");
        assertEquals(1, run("import", input[0], input[1], root.resolve("bank-import.json"), true));
        assertTrue(store.chest(1).isPresent()); assertEquals(blob("\n"), original(1));
    }
    @Test void fixtureGateRejectsMissingMarkerMysqlMemOutsideAndDangerousUrlOptions() throws Exception {
        assertThrows(Exception.class, () -> ColdGate.fixture(root, "jdbc:mysql://localhost/private", "com.mysql.cj.jdbc.Driver"));
        assertThrows(Exception.class, () -> ColdGate.fixture(root, "jdbc:h2:mem:test", "org.h2.Driver"));
        assertThrows(Exception.class, () -> ColdGate.fixture(root, properties.getProperty("jdbc_url") + ";INIT=RUNSCRIPT FROM 'secret'", "org.h2.Driver"));
        assertThrows(Exception.class, () -> ColdGate.fixture(root.resolve("missing"), properties.getProperty("jdbc_url"), "org.h2.Driver"));
        Files.delete(root.resolve(".justteams-synthetic-fixture"));
        assertThrows(Exception.class, () -> ColdGate.fixture(root, properties.getProperty("jdbc_url"), "org.h2.Driver"));
    }
    @Test void dockerGateRequiresRealTypedStoppedStateAndStableStartAndRefusesUnavailable() throws Exception {
        String time = "2026-10-03T00:00:00Z";
        assertThrows(Exception.class, () -> ColdGate.docker("unallowlisted", name -> new ColdGate.State(false, false, false, time)));
        assertThrows(Exception.class, () -> ColdGate.docker("pixelclub-arclight", name -> { throw new java.io.IOException("secret"); }));
        for (ColdGate.State state : java.util.List.of(new ColdGate.State(true, false, false, time), new ColdGate.State(false, true, false, time), new ColdGate.State(false, false, true, time))) assertThrows(Exception.class, () -> ColdGate.docker("pixelclub-fabric", name -> state));
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        ColdGate gate = ColdGate.docker("pixelclub-dev", name -> new ColdGate.State(false, false, false, counter.getAndIncrement() == 0 ? time : "2026-10-03T00:00:01Z"));
        assertThrows(Exception.class, gate::check);
        assertThrows(Exception.class, () -> ColdGate.parseState("{\"Running\":\"false\",\"Restarting\":false,\"Paused\":false,\"StartedAt\":\"" + time + "\"}"));
        ColdGate.docker("pixelclub-arclight", name -> new ColdGate.State(false, false, false, time)).check();
    }
    @Test void errorsNeverPrintPrivatePropertiesOrExceptionText() throws Exception {
        properties.setProperty("password", "SYNTHETIC_SECRET_PASSWORD"); saveConfig();
        assertEquals(1, run("export-native", null, null, root.resolve("refused.json"), false));
        assertEquals("COLD_CHESTS_REFUSED" + System.lineSeparator(), stderr.toString(StandardCharsets.UTF_8));
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertFalse(stderr.toString(StandardCharsets.UTF_8).contains("SYNTHETIC_SECRET_PASSWORD"));
    }
    @Test void strictCodecRejectsDuplicateKeysReservedTeamsAndUnknownEnvelopeFields() throws Exception {
        assertThrows(Exception.class, () -> ColdFiles.inventory("{\"format\":1,\"format\":1}"));
        JsonObject body = ColdFiles.parse(inventory()).getAsJsonObject(); body.addProperty("unknown", true);
        assertThrows(Exception.class, () -> ColdFiles.inventory(ColdFiles.GSON.toJson(body)));
        JsonObject document = document(); JsonObject row = new JsonObject(); row.addProperty("team_id", Integer.MAX_VALUE); document.getAsJsonArray("rows").add(row);
        assertThrows(Exception.class, () -> ColdFiles.rows(document));
    }

    private int run(String mode, Path snapshot, Path artifact, Path output, boolean apply) {
        var args = new ArrayList<String>(java.util.List.of(mode, "--properties", config.toString(), "--fixture-root", root.toString(), "--output", output.toString()));
        if (snapshot != null) { args.add("--snapshot"); args.add(snapshot.toString()); }
        if (artifact != null) { args.add("--artifact"); args.add(artifact.toString()); }
        if (apply) args.add("--apply");
        stdout = new ByteArrayOutputStream(); stderr = new ByteArrayOutputStream();
        return ColdChests.run(args.toArray(String[]::new), new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }
    private Path[] forward(Map<Integer, String> originals) throws Exception {
        Path snapshot = root.resolve(UUID.randomUUID() + "-donor.json"), artifact = root.resolve(UUID.randomUUID() + "-forward.json");
        JsonObject donor = document(); donor.addProperty("cold_verified", true); donor.addProperty("snapshot_utc", "2026-10-03T00:00:00Z");
        for (var entry : new java.util.TreeMap<>(originals).entrySet()) {
            JsonObject row = new JsonObject(); row.addProperty("team_id", entry.getKey()); row.addProperty("blob", entry.getValue()); row.addProperty("source_sha256", ColdFiles.sha(entry.getValue())); donor.getAsJsonArray("rows").add(row);
        }
        write(snapshot, donor); JsonObject converted = document(); converted.addProperty("mode", "forward"); converted.addProperty("source_file_sha256", ColdFiles.sha(Files.readAllBytes(snapshot)));
        for (var entry : new java.util.TreeMap<>(originals).entrySet()) {
            JsonObject row = new JsonObject(); row.addProperty("team_id", entry.getKey()); row.addProperty("inventory_json", inventory()); row.addProperty("original_sha256", ColdFiles.sha(entry.getValue())); converted.getAsJsonArray("rows").add(row);
        }
        write(artifact, converted); return new Path[]{snapshot, artifact};
    }
    private Path reverse(Path snapshot) throws Exception {
        JsonObject output = document(); output.addProperty("mode", "reverse"); output.addProperty("source_file_sha256", ColdFiles.sha(Files.readAllBytes(snapshot)));
        for (var entry : ColdFiles.rows(ColdFiles.read(snapshot).json()).entrySet()) {
            JsonObject row = entry.getValue().deepCopy(); row.remove("generation"); row.addProperty("reverse_blob", blob("\n")); row.addProperty("reverse_sha256", ColdFiles.sha(blob("\n"))); output.getAsJsonArray("rows").add(row);
        }
        Path result = root.resolve(UUID.randomUUID() + "-reverse.json"); write(result, output); return result;
    }
    private static JsonObject document() { JsonObject document = new JsonObject(); document.addProperty("format", 1); document.add("rows", new JsonArray()); return document; }
    private static String inventory() throws Exception {
        JsonObject object = new JsonObject(); object.addProperty("format", 1); object.addProperty("size", 9); JsonArray slots = new JsonArray(); for (int i = 0; i < 9; i++) slots.add(JsonNull.INSTANCE); object.add("slots", slots); object.addProperty("sha256", ColdFiles.semanticHash(object)); return ColdFiles.GSON.toJson(object);
    }
    private static String blob(String lineEnding) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (ObjectOutputStream stream = new ObjectOutputStream(bytes)) { stream.writeInt(9); for (int i = 0; i < 9; i++) stream.writeObject(null); }
        return Base64.getEncoder().encodeToString(bytes.toByteArray()) + lineEnding;
    }
    private static void write(Path file, JsonObject value) throws Exception { Files.write(file, ColdFiles.bytes(value)); }
    private void saveConfig() throws Exception {
        try (var writer = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) { properties.store(writer, "synthetic only"); }
        if (Files.getFileStore(config).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(config, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
    private int newTeam(String name, String tag) throws Exception { return store.create(name, tag, UUID.randomUUID(), "OtherOwner", "fixture").id(); }
    private void donor(int id, String value) throws Exception { try (Connection connection = factory.open(); var statement = connection.prepareStatement("INSERT INTO donut_team_enderchest(team_id,inventory_data) VALUES(?,?)")) { statement.setInt(1, id); statement.setString(2, value); statement.executeUpdate(); } }
    private String original(int id) throws Exception { var rows = store.rows("team_enderchest", "team_id", id); return rows.isEmpty() ? null : (String) rows.getFirst().get("inventory_data"); }
    private void sql(String sql) throws Exception { try (Connection connection = factory.open(); var statement = connection.createStatement()) { statement.execute(sql); } }
}
