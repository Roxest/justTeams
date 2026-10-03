package com.pixelclub.teams.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.pixelclub.teams.store.ConnectionFactory;
import com.pixelclub.teams.store.Member;
import com.pixelclub.teams.store.TeamStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Extends only the newly bootstrapped, marked, local four-actor canary fixture. */
public final class CanaryFixture {
    private static final Path ROOT = Path.of("F:/Pixelclub/scratch/justteams-cutover-20261002/private-dev-canary-20261003/deploy");
    private static final Path RICH = Path.of("F:/Pixelclub/chest-codec-rig-arclight-20261002-proof/chest-conversion/fixtures.json");
    private static final String RICH_SHA = "8f96d5ab290b235d51202c411fe3b6f27fa91421d28136a05e912c4d5a4a5103";
    private static final Set<String> ORIGINAL_NAMES = Set.of("JTMoneyOwner", "JTMoneyMember", "JTMoneyCo");
    private static final String EYES = "PxcEyes2";

    private CanaryFixture() { }

    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException();
            prepare();
        } catch (Exception failure) {
            // No JDBC/source contents, exception messages or nested causes are printed.
            System.err.println("CANARY_FIXTURE_REFUSED");
            System.exit(1);
        }
    }

    private static void prepare() throws Exception {
        Path root = ROOT.toRealPath();
        if (!root.equals(ROOT.normalize()) || !Files.isRegularFile(root.resolve(".justteams-synthetic-fixture"), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
        Path database = root.resolve("config/JustTeams/data/teams-pxc");
        Path file = Path.of(database + ".mv.db");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.exists(Path.of(database + ".lock.db"))) throw new IllegalArgumentException();
        Path proofPath = root.getParent().resolve("fixture-proof.json");
        if (Files.exists(proofPath)) throw new IllegalArgumentException();
        Driver driver = (Driver) Class.forName("eu.kotori.justTeams.libs.h2.Driver").getDeclaredConstructor().newInstance();
        Properties properties = new Properties(); properties.setProperty("user", "sa"); properties.setProperty("password", "");
        String url = "jdbc:h2:file:" + database.toString().replace('\\', '/') + ";MODE=MySQL;AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE";
        ConnectionFactory factory = () -> {
            Connection connection = driver.connect(url, properties);
            if (connection == null) throw new IllegalArgumentException();
            return connection;
        };
        TeamStore store = new TeamStore(factory, "donut_");
        store.validateSchema();
        var team = store.team(1).orElseThrow();
        if (team.tier() != 2 || Double.compare(team.balance(), 123.005d) != 0 || !team.owner().equals(offline("JTMoneyOwner")) || !store.pendingOperations().isEmpty() || store.chest(1).isPresent()) throw new IllegalArgumentException();
        Set<UUID> originalIds = new HashSet<>(); ORIGINAL_NAMES.forEach(name -> originalIds.add(offline(name)));
        if (!ids(store.members(1)).equals(originalIds) || !cacheIds(factory).equals(originalIds)) throw new IllegalArgumentException();

        byte[] richBytes = Files.readAllBytes(RICH);
        if (!ColdFiles.sha(richBytes).equals(RICH_SHA)) throw new IllegalArgumentException();
        JsonArray rows = ColdFiles.read(RICH).json().getAsJsonArray("rows");
        if (rows.size() != 1 || rows.get(0).getAsJsonObject().get("team_id").getAsInt() != Integer.MAX_VALUE) throw new IllegalArgumentException();
        String originalInventory = rows.get(0).getAsJsonObject().get("inventory_json").getAsString();
        ColdFiles.inventory(originalInventory);
        JsonObject chest = ColdFiles.parse(originalInventory).getAsJsonObject();
        if (chest.get("size").getAsInt() != 27 || chest.getAsJsonArray("slots").size() != 27) throw new IllegalArgumentException();
        chest.remove("sha256");
        for (int slot = 27; slot < 36; slot++) chest.getAsJsonArray("slots").add(JsonNull.INSTANCE);
        chest.addProperty("size", 36);
        chest.addProperty("sha256", ColdFiles.semanticHash(chest));
        String inventory = ColdFiles.GSON.toJson(chest);
        ColdFiles.inventory(inventory);

        UUID eyes = offline(EYES);
        if (!store.insertRow("player_cache", Map.of("player_uuid", eyes, "player_name", EYES, "last_seen", new Timestamp(System.currentTimeMillis()))) || !store.joinPublic(eyes, 1, 10) || !store.setMemberFlags(1, eyes, Map.of("can_use_enderchest", true, "can_withdraw", false))) throw new IllegalArgumentException();
        if (!store.saveChest(1, 1, inventory, null, 0)) throw new IllegalArgumentException();
        Set<UUID> expected = new HashSet<>(originalIds); expected.add(eyes);
        store.validateSchema();
        Member member = store.member(eyes).orElseThrow();
        var storedChest = store.chest(1).orElseThrow();
        if (!ids(store.members(1)).equals(expected) || !cacheIds(factory).equals(expected) || !member.role().equals("MEMBER") || !member.flag("can_use_enderchest") || member.flag("can_withdraw") || !EYES.equals(member.name()) || !store.pendingOperations().isEmpty() || !storedChest.inventoryJson().equals(inventory) || storedChest.generation() != 1 || storedChest.originalSha256() != null || Double.compare(store.team(1).orElseThrow().balance(), 123.005d) != 0) throw new IllegalArgumentException();
        JsonObject proof = new JsonObject();
        proof.addProperty("synthetic_only", true); proof.addProperty("source_runtime_database_copied", false); proof.addProperty("schema_tables", store.requiredSchema().size());
        proof.addProperty("members", 4); proof.addProperty("pending_operations", 0); proof.addProperty("team_id", 1); proof.addProperty("tier", 2); proof.addProperty("opening_balance", 123.005d);
        proof.addProperty("eyes_name", EYES); proof.addProperty("eyes_offline_uuid", eyes.toString()); proof.addProperty("eyes_role", "MEMBER"); proof.addProperty("eyes_can_use_enderchest", true); proof.addProperty("eyes_can_withdraw", false);
        proof.addProperty("chest_slots", 36); proof.addProperty("chest_generation", 1); proof.addProperty("chest_inventory_sha256", ColdFiles.sha(inventory)); proof.addProperty("rich_fixture_file_sha256", RICH_SHA);
        proof.addProperty("database_sha256", ColdFiles.sha(Files.readAllBytes(file))); proof.addProperty("auth_database_copied", false); proof.addProperty("player_data_copied", false); proof.addProperty("wallet_created", false);
        proof.addProperty("minecraft_decode_in_this_step", false); proof.addProperty("bank_wal_copied", false); proof.addProperty("chest_wal_copied", false);
        Files.write(proofPath, ColdFiles.bytes(proof), StandardOpenOption.CREATE_NEW);
        System.out.println(ColdFiles.GSON.toJson(proof));
    }

    private static UUID offline(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); }
    private static Set<UUID> ids(java.util.List<Member> members) { Set<UUID> ids = new HashSet<>(); members.forEach(member -> ids.add(member.uuid())); return ids; }
    private static Set<UUID> cacheIds(ConnectionFactory factory) throws Exception {
        Set<UUID> ids = new HashSet<>();
        try (Connection connection = factory.open(); PreparedStatement query = connection.prepareStatement("SELECT player_uuid FROM donut_player_cache"); ResultSet result = query.executeQuery()) {
            while (result.next()) ids.add(UUID.fromString(result.getString(1)));
        }
        return ids;
    }
}
