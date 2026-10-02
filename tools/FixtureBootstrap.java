import com.pixelclub.teams.store.ConnectionFactory;
import com.pixelclub.teams.store.Member;
import com.pixelclub.teams.store.Team;
import com.pixelclub.teams.store.TeamStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Standalone JDBC-only synthetic fixture bootstrap. It never reads a runtime config.
 * Compile: javac -cp build/classes/java/main -d build/fixture-bootstrap tools/FixtureBootstrap.java
 * Run with the exact original jar on the classpath; see port/store-digest.md for PowerShell commands.
 */
public final class FixtureBootstrap {
    private static final String MARKER = ".justteams-synthetic-fixture";
    private static final Path DATABASE_RELATIVE = Path.of("config", "JustTeams", "data", "teams-pxc");
    private static final String DRIVER = "eu.kotori.justTeams.libs.h2.Driver";
    private static final String PREFIX = "donut_";
    private static final Timestamp CREATED = Timestamp.from(Instant.parse("2026-10-02T17:00:00Z"));
    private static final String OWNER_NAME = "JTMoneyOwner";
    private static final String MEMBER_NAME = "JTMoneyMember";
    private static final String CO_NAME = "JTMoneyCo";

    private FixtureBootstrap() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Usage: FixtureBootstrap <absolute-local-fixture-root> [config/JustTeams/data/teams-pxc]");
        Path rootArgument = Path.of(args[0]);
        if (!rootArgument.isAbsolute() || networkPath(rootArgument)) throw new IllegalArgumentException("Fixture root must be an absolute local directory");
        Path root = rootArgument.toRealPath();
        if (networkPath(root) || !Files.isDirectory(root)) throw new IllegalArgumentException("Fixture root must be local");
        if (!Files.isRegularFile(root.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Missing explicit synthetic fixture marker: " + MARKER);
        if (args.length == 2 && !Path.of(args[1]).normalize().equals(DATABASE_RELATIVE)) throw new IllegalArgumentException("Only the synthetic teams-pxc database path is permitted");
        Path database = root.resolve(DATABASE_RELATIVE).normalize();
        if (database.toString().indexOf(';') >= 0) throw new IllegalArgumentException("Fixture path cannot contain JDBC URL options");
        Path parent = database.getParent();
        Path ancestor = parent;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(root)) throw new IllegalArgumentException("Fixture database escapes its marked root");
        Files.createDirectories(parent);
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(root) || networkPath(realParent)) throw new IllegalArgumentException("Fixture database must remain inside its local root");
        database = realParent.resolve(database.getFileName());
        for (String extension : new String[]{"", ".mv.db", ".h2.db", ".lock.db", ".trace.db"}) {
            if (Files.exists(Path.of(database + extension), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Refusing existing fixture database artifact: " + extension);
        }
        Driver driver = (Driver) Class.forName(DRIVER).getDeclaredConstructor().newInstance();
        String url = "jdbc:h2:file:" + database.toString().replace('\\', '/') + ";MODE=MySQL;AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE";
        Properties properties = new Properties();
        properties.setProperty("user", "sa");
        properties.setProperty("password", "");
        ConnectionFactory factory = () -> {
            Connection connection = driver.connect(url, properties);
            if (connection == null) throw new SQLException("Exact shaded H2 driver rejected fixture URL");
            return connection;
        };
        TeamStore store = new TeamStore(factory, PREFIX);
        createDonorSchema(factory, store.requiredSchema());
        store.provisionAdditiveTables();
        seed(factory);
        // All writer connections are closed before this independent file reopening/readback.
        store.validateSchema();
        verify(store);
        System.out.println("Synthetic fixture verified: tables=22 members=3 team_id=1 balance=123.005 driver=" + DRIVER);
        System.out.println("Database base: " + database);
    }

    private static boolean networkPath(Path path) {
        String text = path.toString();
        return text.startsWith("\\\\") || text.startsWith("//");
    }

    private static void createDonorSchema(ConnectionFactory factory, Map<String, String> templates) throws SQLException {
        if (templates.size() != 22) throw new SQLException("Unexpected donor fixture schema count");
        try (Connection connection = factory.open(); Statement statement = connection.createStatement()) {
            if (!connection.getMetaData().getURL().startsWith("jdbc:h2:file:")) throw new SQLException("Fixture bootstrap permits local H2 files only");
            for (var entry : templates.entrySet()) {
                String suffix = entry.getKey().substring(PREFIX.length());
                String definition = entry.getValue();
                String extra = "";
                if (definition.startsWith("team_id ") || definition.contains(", team_id ")) extra += ", FOREIGN KEY(team_id) REFERENCES donut_teams(id) ON DELETE CASCADE";
                switch (suffix) {
                    case "team_warps" -> extra += ", UNIQUE(team_id,warp_name)";
                    case "team_blacklist", "team_invites", "join_requests" -> extra += ", UNIQUE(team_id,player_uuid)";
                    case "team_custom_data" -> extra += ", PRIMARY KEY(team_id,data_key)";
                    case "team_quest_progress" -> extra += ", PRIMARY KEY(team_id,quest_id)";
                    case "team_allies" -> extra += ", UNIQUE(team_id_1,team_id_2), FOREIGN KEY(team_id_1) REFERENCES donut_teams(id) ON DELETE CASCADE, FOREIGN KEY(team_id_2) REFERENCES donut_teams(id) ON DELETE CASCADE";
                    case "team_ally_requests" -> extra += ", UNIQUE(sender_team_id,target_team_id), FOREIGN KEY(sender_team_id) REFERENCES donut_teams(id) ON DELETE CASCADE, FOREIGN KEY(target_team_id) REFERENCES donut_teams(id) ON DELETE CASCADE";
                    default -> { }
                }
                statement.execute("CREATE TABLE `" + entry.getKey() + "` (" + definition + extra + ")");
            }
        }
    }

    private static void seed(ConnectionFactory factory) throws SQLException {
        try (Connection connection = factory.open()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Object> team = new LinkedHashMap<>();
                team.put("id", 1); team.put("name", "JTMoneyFixture"); team.put("tag", "JTM"); team.put("owner_uuid", offline(OWNER_NAME));
                team.put("home_location", null); team.put("home_server", "fixture-local"); team.put("pvp_enabled", false); team.put("is_public", true); team.put("glow_enabled", false); team.put("color", "white");
                team.put("gradient_start", "#123456"); team.put("gradient_end", "#654321"); team.put("created_at", CREATED); team.put("description", "Synthetic local fixture");
                team.put("balance", 123.005d); team.put("kills", 7); team.put("deaths", 2); team.put("join_fee_enabled", false); team.put("join_fee_amount", 0d); team.put("tier", 2); team.put("points", 11L); team.put("alias", "fixture-alias"); team.put("accept_requests", true);
                insert(connection, "teams", team);
                seedProfile(connection, OWNER_NAME, "OWNER", true, true, true, true, true, true, true);
                seedProfile(connection, MEMBER_NAME, "MEMBER", false, false, false, false, false, false, false);
                seedProfile(connection, CO_NAME, "CO_OWNER", false, true, false, true, false, false, false);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    private static void seedProfile(Connection connection, String name, String role, boolean withdraw, boolean setHome,
                                    boolean editCoOwners, boolean editMembers, boolean promote, boolean demote, boolean promoteCoOwner) throws SQLException {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("player_uuid", offline(name)); member.put("team_id", 1); member.put("role", role); member.put("join_date", CREATED);
        member.put("can_withdraw", withdraw); member.put("can_use_enderchest", true); member.put("can_set_home", setHome); member.put("can_use_home", true);
        member.put("can_promote_to_co_owner", promoteCoOwner); member.put("can_edit_members", editMembers); member.put("can_edit_co_owners", editCoOwners); member.put("can_kick_members", editMembers); member.put("can_promote_members", promote); member.put("can_demote_members", demote);
        insert(connection, "team_members", member);
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("player_uuid", offline(name)); cache.put("player_name", name); cache.put("last_seen", CREATED);
        insert(connection, "player_cache", cache);
    }

    private static void insert(Connection connection, String suffix, Map<String, Object> values) throws SQLException {
        String columns = String.join(",", values.keySet().stream().map(column -> "`" + column + "`").toList());
        String markers = String.join(",", java.util.Collections.nCopies(values.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO `" + PREFIX + suffix + "` (" + columns + ") VALUES (" + markers + ")")) {
            int index = 1;
            for (Object value : values.values()) statement.setObject(index++, value instanceof UUID uuid ? uuid.toString() : value);
            if (statement.executeUpdate() != 1) throw new SQLException("Unexpected fixture insert result");
        }
    }

    private static UUID offline(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static void verify(TeamStore store) throws SQLException {
        Team team = store.team(1).orElseThrow(() -> new SQLException("Synthetic team missing"));
        if (!team.owner().equals(offline(OWNER_NAME)) || Double.doubleToLongBits(team.balance()) != Double.doubleToLongBits(123.005d) || store.members(1).size() != 3) throw new SQLException("Synthetic team file readback mismatch");
        Member owner = store.member(offline(OWNER_NAME)).orElseThrow();
        Member member = store.member(offline(MEMBER_NAME)).orElseThrow();
        Member co = store.member(offline(CO_NAME)).orElseThrow();
        if (!owner.role().equals("OWNER") || !owner.flag("can_withdraw") || !member.role().equals("MEMBER") || member.flag("can_withdraw") || !co.role().equals("CO_OWNER") || co.flag("can_withdraw")) throw new SQLException("Synthetic authorization flags mismatch");
        if (!OWNER_NAME.equals(owner.name()) || !MEMBER_NAME.equals(member.name()) || !CO_NAME.equals(co.name())) throw new SQLException("Synthetic cache identity mismatch");
    }
}
