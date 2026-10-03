package com.pixelclub.teams.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.pixelclub.teams.store.ConnectionFactory;
import com.pixelclub.teams.store.TeamStore;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Standalone host-Java cold import/export/rollback; no Minecraft, provider or player calls. */
public final class ColdChests {
    private static final String ADDON = "pxc_teams_chests";
    private static final String OPS = "pxc_teams_operations";
    private static final Set<String> TERMINAL = Set.of("DONE", "CANCELLED", "COMPENSATED", "COMPLETED", "REJECTED", "FAILED");
    private static final Set<String> DRIVERS = Set.of("org.h2.Driver", "eu.kotori.justTeams.libs.h2.Driver", "com.mysql.cj.jdbc.Driver", "org.mariadb.jdbc.Driver");
    record Options(String mode, Path properties, Path snapshot, Path artifact, Path output, String container, Path fixtureRoot, boolean apply) {}
    record NativeRow(int teamId, int format, String json, String originalHash, long generation) {}
    record Config(String url, String username, String password, String driver, String prefix) {
        @Override public String toString() { return "ColdChests.Config[private]"; }
        Connection open() throws Exception {
            Driver instance = (Driver) Class.forName(driver).getDeclaredConstructor().newInstance();
            Properties values = new Properties(); values.setProperty("user", username); values.setProperty("password", password);
            Connection connection = instance.connect(url, values);
            if (connection == null) throw new SQLException();
            return connection;
        }
    }
    interface BeforeCommit { void check(Connection connection) throws Exception; }

    private ColdChests() {}
    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }
    public static int run(String[] args, PrintStream output, PrintStream errors) {
        try {
            Options options = options(args);
            Config config = config(options.properties());
            targetBinding(options.container(), config.url());
            ColdGate gate = options.fixtureRoot() == null ? ColdGate.docker(options.container(), !options.mode().equals("export-native")) : ColdGate.fixture(options.fixtureRoot(), config.url(), config.driver());
            if (Files.exists(options.output())) throw new IllegalArgumentException();
            JsonObject proof = switch (options.mode()) {
                case "import" -> importNative(options, config, gate);
                case "export-native" -> exportNative(options, config, gate);
                case "rollback" -> rollback(options, config, gate);
                default -> throw new IllegalArgumentException();
            };
            output.println(ColdFiles.GSON.toJson(proof));
            return 0;
        } catch (Throwable failure) {
            // JDBC, filesystem, Docker and JSON errors can contain private URLs or contents.
            errors.println("COLD_CHESTS_REFUSED");
            return 1;
        }
    }

    static Options options(String[] args) {
        if (args.length < 1 || !Set.of("import", "export-native", "rollback").contains(args[0])) throw new IllegalArgumentException();
        Map<String, String> values = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String key = args[i];
            if (!Set.of("--properties", "--snapshot", "--artifact", "--output", "--container", "--fixture-root", "--apply").contains(key)) throw new IllegalArgumentException();
            String value = key.equals("--apply") ? "true" : ++i < args.length ? args[i] : null;
            if (value == null || value.isBlank() || values.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
        }
        boolean mutating = !args[0].equals("export-native");
        boolean apply = values.containsKey("--apply");
        if (mutating && !apply || values.containsKey("--container") == values.containsKey("--fixture-root")) throw new IllegalArgumentException();
        Path snapshot = values.containsKey("--snapshot") ? Path.of(values.get("--snapshot")) : null;
        Path artifact = values.containsKey("--artifact") ? Path.of(values.get("--artifact")) : null;
        if (mutating && (snapshot == null || artifact == null)) throw new IllegalArgumentException();
        return new Options(args[0], Path.of(Objects.requireNonNull(values.get("--properties"))), snapshot, artifact,
                Path.of(Objects.requireNonNull(values.get("--output"))).toAbsolutePath().normalize(), values.get("--container"),
                values.containsKey("--fixture-root") ? Path.of(values.get("--fixture-root")) : null, apply);
    }
    /** A stopped dev container must never attest a known shared production MySQL catalog. */
    static void targetBinding(String container, String url) throws Exception {
        if (!"pixelclub-arclight-dev".equals(container) || !(url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:"))) return;
        if (!(url.startsWith("jdbc:mysql://") || url.startsWith("jdbc:mariadb://"))) throw new IllegalArgumentException();
        java.net.URI address = new java.net.URI(url.substring("jdbc:".length()));
        String path = address.getRawPath();
        if (address.getRawAuthority() == null || address.getRawAuthority().isEmpty() || address.getRawFragment() != null || path == null || !path.startsWith("/") || path.length() < 2) throw new IllegalArgumentException();
        String database = decodeUrl(path.substring(1));
        if (database.indexOf('/') >= 0 || database.indexOf('\\') >= 0 || database.indexOf('%') >= 0 || database.indexOf(';') >= 0 || database.indexOf('?') >= 0 || database.indexOf('#') >= 0 || database.isBlank() || !database.equals(database.strip()) || database.chars().anyMatch(character -> character < 32)) throw new IllegalArgumentException();
        if (database.equalsIgnoreCase("pixelclub")) throw new IllegalArgumentException();
        String query = address.getRawQuery();
        if (query != null) for (String entry : query.split("&", -1)) {
            String[] pair = entry.split("=", 2);
            String key = decodeUrl(pair[0]);
            if (Set.of("database", "databasename", "db", "catalog").contains(key.toLowerCase(java.util.Locale.ROOT))) {
                if (pair.length < 2) throw new IllegalArgumentException();
                String selected = decodeUrl(pair[1]);
                if (selected.equalsIgnoreCase("pixelclub") || !selected.equalsIgnoreCase(database)) throw new IllegalArgumentException();
            }
        }
    }
    private static String decodeUrl(String value) throws Exception {
        // URLDecoder silently replaces malformed UTF-8. Refuse replacement/control ambiguities.
        String decoded = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        if (decoded.indexOf('\ufffd') >= 0) throw new IllegalArgumentException();
        return decoded;
    }
    private static Config config(Path path) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            var permissions = Files.getPosixFilePermissions(path);
            if (permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ) || permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)) throw new IllegalArgumentException();
        }
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { values.load(reader); }
        if (!values.stringPropertyNames().equals(Set.of("jdbc_url", "username", "password", "driver", "prefix"))) throw new IllegalArgumentException();
        String prefix = values.getProperty("prefix"), driver = values.getProperty("driver"), url = values.getProperty("jdbc_url");
        if (!prefix.matches("[A-Za-z0-9_]+") || !DRIVERS.contains(driver) || !(url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:") || url.startsWith("jdbc:h2:file:"))) throw new IllegalArgumentException();
        return new Config(url, values.getProperty("username"), values.getProperty("password"), driver, prefix);
    }

    private static boolean keepOriginal(JsonObject exported) throws Exception {
        return ColdFiles.longInteger(exported.get("generation")) == 1 && ColdFiles.hash(exported, "original_sha256", true) != null;
    }
    private static ConnectionFactory factory(Config config, ColdGate gate, BeforeCommit extra) {
        return () -> {
            try {
                Connection delegate = config.open();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("commit")) {
                        try { requireSettled(delegate, true); if (extra != null) extra.check(delegate); gate.check(); }
                        catch (Exception denied) { throw new SQLException("Cold guard refused commit"); }
                    }
                    try { return method.invoke(delegate, arguments); }
                    catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                });
            } catch (Exception refused) { throw new SQLException("Cold connection refused"); }
        };
    }

    private static JsonObject importNative(Options options, Config config, ColdGate gate) throws Exception {
        ColdFiles.Document snapshot = ColdFiles.read(options.snapshot()), artifact = ColdFiles.read(options.artifact());
        ColdFiles.requireCold(snapshot.json());
        if (!ColdFiles.text(artifact.json(), "mode").equals("forward") || !ColdFiles.hash(artifact.json(), "source_file_sha256", false).equals(ColdFiles.sha(snapshot.bytes()))) throw new IllegalArgumentException();
        Map<Integer, JsonObject> originals = ColdFiles.rows(snapshot.json()), converted = ColdFiles.rows(artifact.json());
        if (!originals.keySet().equals(converted.keySet())) throw new IllegalArgumentException();
        var seeds = new ArrayList<TeamStore.ChestSeed>();
        for (int id : originals.keySet()) {
            String original = ColdFiles.text(originals.get(id), "blob"), hash = ColdFiles.hash(originals.get(id), "source_sha256", false);
            if (!ColdFiles.sha(original).equals(hash) || !hash.equals(ColdFiles.hash(converted.get(id), "original_sha256", false))) throw new IllegalArgumentException();
            String json = ColdFiles.text(converted.get(id), "inventory_json"); ColdFiles.inventory(json);
            seeds.add(new TeamStore.ChestSeed(id, 1, json, hash));
        }
        BeforeCommit revalidate = connection -> verifyDonors(connection, config.prefix(), originals, true);
        ConnectionFactory factory = factory(config, gate, revalidate);
        TeamStore store = new TeamStore(factory, config.prefix()); store.validateSchema();
        try (Connection connection = factory.open()) {
            connection.setAutoCommit(false); requireSettled(connection, false); verifyDonors(connection, config.prefix(), originals, false); connection.rollback();
        }
        gate.check(); store.provisionAdditiveTables(); gate.check();
        try (FileChannel proofFile = newOutput(options.output())) {
            if (!store.importChests(seeds)) throw new IllegalArgumentException();
            gate.check();
            JsonObject report = proof("import", seeds.size(), seeds.isEmpty() ? 0 : 1, seeds.isEmpty() ? 0 : 1);
            report.addProperty("source_sha256", ColdFiles.sha(snapshot.bytes())); report.addProperty("artifact_sha256", ColdFiles.sha(artifact.bytes()));
            byte[] bytes = ColdFiles.bytes(report); write(proofFile, bytes);
            report.addProperty("sha256", ColdFiles.sha(bytes));
            return report;
        }
    }

    private static JsonObject exportNative(Options options, Config config, ColdGate gate) throws Exception {
        ConnectionFactory factory = factory(config, gate, null);
        Map<Integer, NativeRow> rows;
        try (Connection connection = factory.open()) {
            connection.setAutoCommit(false); connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            requireSettled(connection, false); rows = nativeRows(connection, false); gate.check(); connection.rollback();
        }
        JsonObject document = new JsonObject(); document.addProperty("format", 1); document.addProperty("cold_verified", true);
        document.addProperty("snapshot_utc", Instant.now().toString()); JsonArray entries = new JsonArray();
        for (NativeRow row : rows.values()) {
            JsonObject value = new JsonObject(); value.addProperty("team_id", row.teamId()); value.addProperty("inventory_json", row.json());
            if (row.originalHash() == null) value.add("original_sha256", JsonNull.INSTANCE); else value.addProperty("original_sha256", row.originalHash());
            value.addProperty("generation", row.generation()); entries.add(value);
        }
        document.add("rows", entries); byte[] bytes = ColdFiles.bytes(document);
        gate.check(); try (FileChannel output = newOutput(options.output())) { write(output, bytes); }
        JsonObject report = proof("export-native", rows.size(), minimum(rows), maximum(rows)); report.addProperty("sha256", ColdFiles.sha(bytes)); return report;
    }

    private static JsonObject rollback(Options options, Config config, ColdGate gate) throws Exception {
        ColdFiles.Document snapshot = ColdFiles.read(options.snapshot()), artifact = ColdFiles.read(options.artifact()); ColdFiles.requireCold(snapshot.json());
        if (!ColdFiles.text(artifact.json(), "mode").equals("reverse") || !ColdFiles.hash(artifact.json(), "source_file_sha256", false).equals(ColdFiles.sha(snapshot.bytes()))) throw new IllegalArgumentException();
        Map<Integer, JsonObject> exported = ColdFiles.rows(snapshot.json()), reversed = ColdFiles.rows(artifact.json());
        if (exported.isEmpty() || !exported.keySet().equals(reversed.keySet())) throw new IllegalArgumentException();
        for (int id : exported.keySet()) {
            JsonObject before = exported.get(id), after = reversed.get(id);
            String json = ColdFiles.text(before, "inventory_json"); ColdFiles.inventory(json);
            if (ColdFiles.longInteger(before.get("generation")) < 1 || !json.equals(ColdFiles.text(after, "inventory_json")) || !Objects.equals(ColdFiles.hash(before, "original_sha256", true), ColdFiles.hash(after, "original_sha256", true))) throw new IllegalArgumentException();
            String blob = ColdFiles.text(after, "reverse_blob");
            if (!blob.matches("[A-Za-z0-9+/=\\r\\n]+") || !ColdFiles.sha(blob).equals(ColdFiles.hash(after, "reverse_sha256", false))) throw new IllegalArgumentException();
            byte[] stream = Base64.getDecoder().decode(blob.replace("\r", "").replace("\n", ""));
            if (stream.length < 8 || (stream[0] & 255) != 0xac || (stream[1] & 255) != 0xed || stream[2] != 0 || stream[3] != 5) throw new IllegalArgumentException();
        }
        ConnectionFactory factory = factory(config, gate, connection -> {
            if (!nativeRows(connection, true).isEmpty()) throw new IllegalArgumentException();
            Map<Integer, String> written = donorRows(connection, config.prefix(), true);
            for (int id : reversed.keySet()) {
                boolean keep = keepOriginal(exported.get(id)); String donor = written.get(id);
                if (keep ? donor == null || !ColdFiles.sha(donor).equals(ColdFiles.hash(exported.get(id), "original_sha256", false)) : !Objects.equals(donor, ColdFiles.text(reversed.get(id), "reverse_blob"))) throw new IllegalArgumentException();
            }
        });
        Map<Integer, NativeRow> current;
        try (FileChannel proofFile = newOutput(options.output()); Connection connection = factory.open()) {
            connection.setAutoCommit(false);
            try {
                requireSettled(connection, true);
                for (int id : exported.keySet()) if (!existsTeam(connection, config.prefix(), id)) throw new IllegalArgumentException();
                current = nativeRows(connection, true);
                if (!current.keySet().equals(exported.keySet())) throw new IllegalArgumentException();
                Map<Integer, String> originals = donorRows(connection, config.prefix(), true);
                for (NativeRow row : current.values()) {
                    JsonObject before = exported.get(row.teamId()); String expectedHash = ColdFiles.hash(before, "original_sha256", true);
                    if (!row.json().equals(ColdFiles.text(before, "inventory_json")) || row.generation() != ColdFiles.longInteger(before.get("generation")) || !Objects.equals(row.originalHash(), expectedHash)) throw new IllegalArgumentException();
                    String donor = originals.get(row.teamId());
                    if (expectedHash == null ? donor != null && !donor.isEmpty() : donor == null || donor.isEmpty() || !ColdFiles.sha(donor).equals(expectedHash)) throw new IllegalArgumentException();
                }
                for (NativeRow row : current.values()) {
                    String blob = ColdFiles.text(reversed.get(row.teamId()), "reverse_blob");
                    String table = donorTable(config.prefix());
                    // Import never rewrites donor rows; a generation-1 imported row was never saved natively, so its exact original bytes stay.
                    if (keepOriginal(exported.get(row.teamId()))) { }
                    else if (originals.containsKey(row.teamId())) update(connection, "UPDATE " + table + " SET inventory_data=? WHERE team_id=?", blob, row.teamId());
                    else update(connection, "INSERT INTO " + table + " (team_id,inventory_data) VALUES (?,?)", row.teamId(), blob);
                    if (update(connection, "DELETE FROM " + ADDON + " WHERE team_id=? AND generation=? AND inventory_json=?", row.teamId(), row.generation(), row.json()) != 1) throw new SQLException();
                }
                connection.commit(); gate.check();
            } catch (Exception failure) { try { connection.rollback(); } catch (SQLException ignored) { } throw failure; }
            JsonObject report = proof("rollback", current.size(), minimum(current), maximum(current));
            report.addProperty("source_sha256", ColdFiles.sha(snapshot.bytes())); report.addProperty("artifact_sha256", ColdFiles.sha(artifact.bytes()));
            byte[] bytes = ColdFiles.bytes(report); write(proofFile, bytes); report.addProperty("sha256", ColdFiles.sha(bytes)); return report;
        }
    }

    private static void verifyDonors(Connection connection, String prefix, Map<Integer, JsonObject> expected, boolean lock) throws Exception {
        Map<Integer, String> all = donorRows(connection, prefix, lock), nonempty = new TreeMap<>();
        all.forEach((id, blob) -> { if (blob != null && !blob.isEmpty()) nonempty.put(id, blob); });
        if (!nonempty.keySet().equals(expected.keySet())) throw new IllegalArgumentException();
        for (int id : nonempty.keySet()) if (!nonempty.get(id).equals(ColdFiles.text(expected.get(id), "blob")) || !ColdFiles.sha(nonempty.get(id)).equals(ColdFiles.hash(expected.get(id), "source_sha256", false))) throw new IllegalArgumentException();
    }
    private static Map<Integer, String> donorRows(Connection connection, String prefix, boolean lock) throws Exception {
        Map<Integer, String> rows = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT team_id,inventory_data FROM " + donorTable(prefix) + " ORDER BY team_id" + (lock ? " FOR UPDATE" : "")); ResultSet result = statement.executeQuery()) {
            while (result.next()) rows.put(result.getInt(1), result.getString(2));
        }
        return rows;
    }
    private static Map<Integer, NativeRow> nativeRows(Connection connection, boolean lock) throws Exception {
        Map<Integer, NativeRow> rows = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT team_id,format_version,inventory_json,original_sha256,generation FROM " + ADDON + " ORDER BY team_id" + (lock ? " FOR UPDATE" : "")); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                int id = result.getInt(1), format = result.getInt(2); String json = result.getString(3), original = result.getString(4); long generation = result.getLong(5);
                if (id < 1 || id == Integer.MAX_VALUE || format != 1 || generation < 1 || original != null && !original.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
                ColdFiles.inventory(json); rows.put(id, new NativeRow(id, format, json, original, generation));
            }
        }
        return rows;
    }
    private static void requireSettled(Connection connection, boolean lock) throws Exception {
        if (!tableExists(connection, OPS)) return;
        try (PreparedStatement statement = connection.prepareStatement("SELECT kind,phase FROM " + OPS + (lock ? " FOR UPDATE" : "")); ResultSet result = statement.executeQuery()) {
            while (result.next()) if (!Set.of("INVITE", "IDENTITY").contains(result.getString(1)) && !TERMINAL.contains(result.getString(2))) throw new IllegalArgumentException();
        }
    }
    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), null, null, new String[]{"TABLE"})) {
            while (result.next()) if (table.equalsIgnoreCase(result.getString("TABLE_NAME"))) return true;
            return false;
        }
    }
    private static boolean existsTeam(Connection connection, String prefix, int team) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM `" + prefix + "teams` WHERE id=? FOR UPDATE")) {
            statement.setInt(1, team); try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }
    private static String donorTable(String prefix) { return "`" + prefix + "team_enderchest`"; }
    private static int update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]); return statement.executeUpdate(); }
    }
    private static FileChannel newOutput(Path output) throws Exception {
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        if (Files.getFileStore(output.getParent()).supportsFileAttributeView("posix")) {
            return FileChannel.open(output, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        }
        return FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    private static void write(FileChannel output, byte[] bytes) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer); output.force(true);
    }
    private static JsonObject proof(String mode, int count, long minimum, long maximum) {
        JsonObject proof = new JsonObject(); proof.addProperty("mode", mode); proof.addProperty("count", count); proof.addProperty("generation_min", minimum); proof.addProperty("generation_max", maximum); return proof;
    }
    private static long minimum(Map<Integer, NativeRow> rows) { return rows.values().stream().mapToLong(NativeRow::generation).min().orElse(0); }
    private static long maximum(Map<Integer, NativeRow> rows) { return rows.values().stream().mapToLong(NativeRow::generation).max().orElse(0); }
}
