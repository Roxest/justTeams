import com.google.gson.*;
import com.pixelclub.teams.store.TeamStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * Cold-lane rehearsal fixture on this PC only (marked H2, --fixture-root mode of the real ColdChests CLI).
 * seed:     22 donor tables, synthetic teams/members/sub-cent balances, EXACT donor chest blobs, cold snapshot file.
 * manifest: per-table row count + canonical row hash (money/members/chests/everything) for before/after diffing.
 * mutate:   legitimate post-cutover state (bank change, changed native chest, new native chest) before rollback.
 * No Docker, server, MySQL, account, provider or real player data other than the already off-server chest blobs.
 */
public final class Rehearsal {
    static final String PREFIX = "donut_";
    static final int NEW_TEAM = 900001;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "seed" -> seed(root, Path.of(args[2]), Path.of(args[3]));
            case "manifest" -> Files.writeString(Path.of(args[2]), new GsonBuilder().setPrettyPrinting().create().toJson(manifest(root)), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            case "mutate" -> mutate(root, Path.of(args[2]));
            case "natives" -> {
                JsonObject out = new JsonObject();
                try (Connection c = open(root); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT team_id,inventory_json,original_sha256,generation FROM pxc_teams_chests ORDER BY team_id")) {
                    while (rs.next()) { JsonObject r = new JsonObject(); r.addProperty("inventory_sha256", sha(rs.getString(2))); r.addProperty("original_sha256", rs.getString(3)); r.addProperty("generation", rs.getLong(4)); out.add(Integer.toString(rs.getInt(1)), r); }
                }
                Files.writeString(Path.of(args[2]), new GsonBuilder().setPrettyPrinting().create().toJson(out), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static Connection open(Path root) throws SQLException {
        Driver driver;
        try { driver = (Driver) Class.forName("eu.kotori.justTeams.libs.h2.Driver").getConstructor().newInstance(); }
        catch (ReflectiveOperationException e) { throw new SQLException("relocated H2 driver unavailable", e); }
        Properties p = new Properties(); p.setProperty("user", "sa"); p.setProperty("password", "");
        return driver.connect("jdbc:h2:file:" + root.resolve("teams").toString().replace('\\', '/') + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE", p);
    }
    static TeamStore store(Path root) { return new TeamStore(() -> open(root), PREFIX); }
    static String sha(byte[] b) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
    static String sha(String s) throws Exception { return sha(s.getBytes(StandardCharsets.UTF_8)); }

    static void seed(Path root, Path donorProof, Path snapshotOut) throws Exception {
        if (Files.exists(root)) throw new IllegalStateException("fresh root required");
        Files.createDirectories(root);
        Files.writeString(root.resolve(".justteams-synthetic-fixture"), "cold-lane rehearsal fixture\n");
        JsonObject donor = JsonParser.parseString(Files.readString(donorProof)).getAsJsonObject();
        List<JsonObject> rows = new ArrayList<>();
        for (JsonElement e : donor.getAsJsonArray("rows")) rows.add(e.getAsJsonObject());
        rows.sort(Comparator.comparingInt(r -> r.get("team_id").getAsInt()));
        try (Connection c = open(root); Statement s = c.createStatement()) {
            for (var t : store(root).requiredSchema().entrySet()) s.execute("CREATE TABLE `" + t.getKey() + "` (" + t.getValue() + ")");
            try (PreparedStatement team = c.prepareStatement("INSERT INTO donut_teams(id,name,tag,owner_uuid,balance) VALUES(?,?,?,?,?)");
                 PreparedStatement member = c.prepareStatement("INSERT INTO donut_team_members(player_uuid,team_id,role,can_withdraw) VALUES(?,?,?,?)");
                 PreparedStatement chest = c.prepareStatement("INSERT INTO donut_team_enderchest(team_id,inventory_data) VALUES(?,?)")) {
                List<Integer> ids = new ArrayList<>(); for (JsonObject r : rows) ids.add(r.get("team_id").getAsInt()); ids.add(NEW_TEAM);
                for (int id : ids) {
                    UUID owner = UUID.nameUUIDFromBytes(("RehearsalOwner:" + id).getBytes(StandardCharsets.UTF_8));
                    team.setInt(1, id); team.setString(2, "Rehearsal" + id); team.setString(3, "R" + id); team.setString(4, owner.toString());
                    team.setDouble(5, 1000 + id + 0.005); team.executeUpdate();
                    member.setString(1, owner.toString()); member.setInt(2, id); member.setString(3, "OWNER"); member.setBoolean(4, true); member.executeUpdate();
                    member.setString(1, UUID.nameUUIDFromBytes(("RehearsalMember:" + id).getBytes(StandardCharsets.UTF_8)).toString()); member.setInt(2, id); member.setString(3, "MEMBER"); member.setBoolean(4, false); member.executeUpdate();
                }
                for (JsonObject r : rows) {
                    String blob = r.get("blob").getAsString();
                    if (!sha(blob).equals(r.get("source_sha256").getAsString())) throw new IllegalStateException("donor blob hash mismatch " + r.get("team_id"));
                    chest.setInt(1, r.get("team_id").getAsInt()); chest.setString(2, blob); chest.executeUpdate();
                }
            }
        }
        // Cold snapshot read back FROM the stopped fixture DB (not copied from the live proof), same shape as chest_export.py.
        JsonObject snapshot = new JsonObject(); snapshot.addProperty("format", 1); snapshot.addProperty("snapshot_utc", java.time.Instant.now().toString());
        snapshot.addProperty("cold_verified", true); snapshot.addProperty("source", "SELECT team_id, inventory_data FROM donut_team_enderchest (rehearsal H2 fixture, JVM-less)");
        JsonArray out = new JsonArray();
        try (Connection c = open(root); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT team_id,inventory_data FROM donut_team_enderchest WHERE inventory_data IS NOT NULL AND inventory_data<>'' ORDER BY team_id")) {
            while (rs.next()) { JsonObject r = new JsonObject(); r.addProperty("team_id", rs.getInt(1)); r.addProperty("blob", rs.getString(2)); r.addProperty("source_sha256", sha(rs.getString(2))); out.add(r); }
        }
        snapshot.add("rows", out);
        Files.write(snapshotOut, new GsonBuilder().disableHtmlEscaping().create().toJson(snapshot).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("{\"seeded_teams\":" + (rows.size() + 1) + ",\"donor_chests\":" + out.size() + ",\"snapshot_sha256\":\"" + sha(Files.readAllBytes(snapshotOut)) + "\"}");
    }

    static JsonObject manifest(Path root) throws Exception {
        JsonObject result = new JsonObject();
        try (Connection c = open(root)) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) { while (rs.next()) if (!"INFORMATION_SCHEMA".equalsIgnoreCase(rs.getString("TABLE_SCHEM"))) tables.add(rs.getString("TABLE_NAME")); }
            Collections.sort(tables);
            for (String table : tables) {
                List<String> lines = new ArrayList<>();
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM `" + table + "`")) {
                    int n = rs.getMetaData().getColumnCount();
                    while (rs.next()) { StringBuilder b = new StringBuilder(); for (int i = 1; i <= n; i++) b.append(rs.getMetaData().getColumnLabel(i)).append('=').append(Objects.toString(rs.getObject(i))).append('\u0001'); lines.add(b.toString()); }
                }
                Collections.sort(lines);
                JsonObject t = new JsonObject(); t.addProperty("rows", lines.size()); t.addProperty("sha256", sha(String.join("\n", lines)));
                result.add(table.toLowerCase(Locale.ROOT), t);
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT SUM(balance), COUNT(*) FROM donut_teams")) { rs.next(); result.addProperty("bank_sum", rs.getBigDecimal(1).toPlainString()); }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT team_id,inventory_data FROM donut_team_enderchest ORDER BY team_id")) {
                JsonObject chests = new JsonObject(); while (rs.next()) chests.addProperty(Integer.toString(rs.getInt(1)), rs.getString(2) == null ? "null" : sha(rs.getString(2)));
                result.add("donor_chest_sha", chests);
            }
        }
        return result;
    }

    static void mutate(Path root, Path out) throws Exception {
        TeamStore store = store(root);
        JsonObject record = new JsonObject();
        try (Connection c = open(root); Statement s = c.createStatement()) {
            int first, second;
            try (ResultSet rs = s.executeQuery("SELECT MIN(team_id), MAX(team_id) FROM donut_team_enderchest")) { rs.next(); first = rs.getInt(1); second = rs.getInt(2); }
            // Legitimate post-cutover money: rollback must keep it, never restore a pre-cutover balance.
            s.executeUpdate("UPDATE donut_teams SET balance=balance+12.345 WHERE id=" + first);
            record.addProperty("bank_changed_team", first); record.addProperty("bank_delta", "12.345");
            var a = store.chest(first).orElseThrow(); var b = store.chest(second).orElseThrow();
            if (!store.saveChest(first, a.formatVersion(), b.inventoryJson(), a.originalSha256(), a.generation())) throw new IllegalStateException("CAS refused change");
            record.addProperty("chest_changed_team", first); record.addProperty("chest_changed_from_generation", a.generation());
            if (!store.saveChest(NEW_TEAM, b.formatVersion(), b.inventoryJson(), null, 0)) throw new IllegalStateException("CAS refused new chest");
            record.addProperty("chest_new_team", NEW_TEAM);
        }
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(record), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println(record);
    }
}
