package eu.kotori.justTeams.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.kotori.justTeams.JustTeamsFabric;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class FabricStorageManager {

    private static final Logger LOGGER = Logger.getLogger(FabricStorageManager.class.getName());
    private final JustTeamsFabric plugin;
    private HikariDataSource dataSource;
    private String storageType = "mysql";

    public static class TeamRecord {
        public int id;
        public String name;
        public String tag;
        public UUID ownerUuid;
        public String homeLocation;
        public boolean pvpEnabled;
        public boolean isPublic;
        public double balance;
        public int kills;
        public int deaths;
        public long points;
        public int tier;
        public String description;
    }

    public static class MemberRecord {
        public UUID playerUuid;
        public int teamId;
        public String role;
        public String playerName;
        public java.sql.Timestamp joinedAt;
        public boolean canWithdraw;
        public boolean canUseEnderchest;
        public boolean canSetHome;
        public boolean canUseHome;
    }

    public static class WarpRecord {
        public int id;
        public int teamId;
        public String name;
        public String location;
        public String password;
    }

    public FabricStorageManager(JustTeamsFabric plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        LOGGER.info("Initializing FabricStorageManager for JustTeams...");

        if (plugin.getConfigManager() != null) {
            this.storageType = plugin.getConfigManager().getString("storage.type", "mysql").toLowerCase();
        }

        String host = plugin.getConfigManager() != null ? plugin.getConfigManager().getString("storage.mysql.host", "localhost") : "localhost";
        int port = plugin.getConfigManager() != null ? plugin.getConfigManager().getInt("storage.mysql.port", 3306) : 3306;
        String database = plugin.getConfigManager() != null ? plugin.getConfigManager().getString("storage.mysql.database", "teams") : "teams";
        String username = plugin.getConfigManager() != null ? plugin.getConfigManager().getString("storage.mysql.username", "root") : "root";
        String password = plugin.getConfigManager() != null ? plugin.getConfigManager().getString("storage.mysql.password", "") : "";
        boolean useSSL = plugin.getConfigManager() != null ? plugin.getConfigManager().getBoolean("storage.mysql.use_ssl", false) : false;

        LOGGER.info("Configured storage type: " + storageType);
        HikariConfig config = new HikariConfig();
        config.setPoolName("JustTeams-Fabric-Pool");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        if ("mysql".equalsIgnoreCase(storageType) || "mariadb".equalsIgnoreCase(storageType)) {
            String driverClass = "com.mysql.cj.jdbc.Driver";
            String jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8mb4",
                host, port, database, useSSL);
            config.setDriverClassName(driverClass);
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(username);
            config.setPassword(password);
            config.setConnectionTestQuery("/* ping */ SELECT 1");
            LOGGER.info("Connecting to MySQL at " + host + ":" + port + "/" + database + " (user: " + username + ")...");
        } else {
            File dataFolder = plugin.getDataFolder().resolve("data").toFile();
            if (!dataFolder.exists()) dataFolder.mkdirs();
            String h2Url = "jdbc:h2:" + dataFolder.getAbsolutePath().replace("\\", "/") + "/teams;AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE";
            config.setDriverClassName("org.h2.Driver");
            config.setJdbcUrl(h2Url);
            LOGGER.info("Connecting to H2 at " + h2Url);
        }

        try {
            this.dataSource = new HikariDataSource(config);
            try (Connection conn = this.dataSource.getConnection()) {
                LOGGER.info("✓ Successfully connected to MySQL database: " + conn.getMetaData().getDatabaseProductName());
                verifyTables(conn);
                return true;
            }
        } catch (Exception e) {
            LOGGER.severe("✗ Failed to connect to database: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void verifyTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS `donut_teams` (`id` INT AUTO_INCREMENT, `name` VARCHAR(128) NOT NULL UNIQUE, `tag` VARCHAR(255) NOT NULL, `owner_uuid` VARCHAR(36) NOT NULL, `home_location` VARCHAR(255), `home_server` VARCHAR(255), `pvp_enabled` BOOLEAN DEFAULT TRUE, `is_public` BOOLEAN DEFAULT FALSE, `glow_enabled` BOOLEAN DEFAULT TRUE, `color` VARCHAR(32) DEFAULT NULL, `gradient_start` VARCHAR(7) DEFAULT NULL, `gradient_end` VARCHAR(7) DEFAULT NULL, `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, `description` VARCHAR(64) DEFAULT NULL, `balance` DOUBLE DEFAULT 0.0, `kills` INT DEFAULT 0, `deaths` INT DEFAULT 0, `join_fee_enabled` BOOLEAN DEFAULT FALSE, `join_fee_amount` DOUBLE DEFAULT 0.0, `tier` INT DEFAULT 1, `points` BIGINT DEFAULT 0, PRIMARY KEY (`id`))");
            stmt.execute("CREATE TABLE IF NOT EXISTS `donut_team_members` (`player_uuid` VARCHAR(36) NOT NULL, `team_id` INT NOT NULL, `role` VARCHAR(16) NOT NULL, `join_date` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, `can_withdraw` BOOLEAN DEFAULT FALSE, `can_use_enderchest` BOOLEAN DEFAULT TRUE, `can_set_home` BOOLEAN DEFAULT FALSE, `can_use_home` BOOLEAN DEFAULT TRUE, `can_promote_to_co_owner` BOOLEAN DEFAULT FALSE, PRIMARY KEY (`player_uuid`), FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");
            stmt.execute("CREATE TABLE IF NOT EXISTS `donut_team_warps` (`id` INT AUTO_INCREMENT, `team_id` INT NOT NULL, `warp_name` VARCHAR(32) NOT NULL, `location` VARCHAR(255) NOT NULL, `server` VARCHAR(255), `password` VARCHAR(64), `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (`id`), FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");
            LOGGER.info("✓ Verified JustTeams MySQL tables (donut_teams, donut_team_members, donut_team_warps)!");
        }
    }

    private String extractValue(String line) {
        int idx = line.indexOf(':');
        if (idx == -1) return "";
        String val = line.substring(idx + 1).trim();
        if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
            if (val.length() >= 2) {
                val = val.substring(1, val.length() - 1);
            }
        }
        return val;
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DataSource not initialized");
        }
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            LOGGER.info("Database connection pool closed.");
        }
    }

    public TeamRecord getTeamByName(String name) {
        String sql = "SELECT * FROM `donut_teams` WHERE LOWER(`name`) = LOWER(?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapTeam(rs);
                }
            }
        } catch (SQLException e) {
            LOGGER.warning("Error fetching team by name: " + e.getMessage());
        }
        return null;
    }

    public TeamRecord getTeamByPlayer(UUID playerUuid) {
        String sql = "SELECT t.* FROM `donut_teams` t JOIN `donut_team_members` m ON t.id = m.team_id WHERE m.player_uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapTeam(rs);
                }
            }
        } catch (SQLException e) {
            LOGGER.warning("Error fetching team by player: " + e.getMessage());
        }
        return null;
    }

    public boolean createTeam(String name, String tag, UUID ownerUuid, String ownerName) {
        String insertTeam = "INSERT INTO `donut_teams` (`name`, `tag`, `owner_uuid`, `pvp_enabled`, `is_public`, `balance`, `tier`) VALUES (?, ?, ?, TRUE, FALSE, 0.0, 1)";
        String insertMember = "INSERT INTO `donut_team_members` (`player_uuid`, `team_id`, `role`, `can_withdraw`, `can_use_enderchest`, `can_set_home`, `can_use_home`, `can_promote_to_co_owner`) VALUES (?, ?, 'OWNER', TRUE, TRUE, TRUE, TRUE, TRUE)";
        
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(insertTeam, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, tag.isEmpty() ? name : tag);
                ps.setString(3, ownerUuid.toString());
                ps.executeUpdate();
                
                int teamId;
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        teamId = rs.getInt(1);
                    } else {
                        conn.rollback();
                        return false;
                    }
                }

                try (PreparedStatement psMem = conn.prepareStatement(insertMember)) {
                    psMem.setString(1, ownerUuid.toString());
                    psMem.setInt(2, teamId);
                    psMem.executeUpdate();
                }

                conn.commit();
                LOGGER.info("Created team in MySQL: " + name + " (ID: " + teamId + ")");
                return true;
            } catch (SQLException e) {
                conn.rollback();
                LOGGER.severe("Error creating team in MySQL: " + e.getMessage());
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOGGER.severe("Connection error creating team: " + e.getMessage());
            return false;
        }
    }

    public boolean disbandTeam(int teamId) {
        String sql = "DELETE FROM `donut_teams` WHERE `id` = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, teamId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Error disbanding team: " + e.getMessage());
            return false;
        }
    }

    public boolean addMember(int teamId, UUID playerUuid, String role) {
        String sql = "INSERT INTO `donut_team_members` (`player_uuid`, `team_id`, `role`, `can_withdraw`, `can_use_enderchest`, `can_set_home`, `can_use_home`) VALUES (?, ?, ?, FALSE, TRUE, FALSE, TRUE)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setInt(2, teamId);
            ps.setString(3, role);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Error adding member: " + e.getMessage());
            return false;
        }
    }

    public List<MemberRecord> getTeamMembers(int teamId) {
        List<MemberRecord> members = new ArrayList<>();
        String sql = "SELECT * FROM `donut_team_members` WHERE `team_id` = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MemberRecord m = new MemberRecord();
                    m.playerUuid = UUID.fromString(rs.getString("player_uuid"));
                    m.teamId = rs.getInt("team_id");
                    m.role = rs.getString("role");
                    m.joinedAt = rs.getTimestamp("join_date");
                    m.canWithdraw = rs.getBoolean("can_withdraw");
                    m.canUseEnderchest = rs.getBoolean("can_use_enderchest");
                    m.canSetHome = rs.getBoolean("can_set_home");
                    m.canUseHome = rs.getBoolean("can_use_home");
                    members.add(m);
                }
            }
        } catch (SQLException e) {
            LOGGER.warning("Error fetching team members: " + e.getMessage());
        }
        return members;
    }

    public boolean removeMember(UUID playerUuid) {
        String sql = "DELETE FROM `donut_team_members` WHERE `player_uuid` = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Error removing member: " + e.getMessage());
            return false;
        }
    }

    public boolean setRole(UUID playerUuid, String role) {
        String sql = "UPDATE `donut_team_members` SET `role` = ? WHERE `player_uuid` = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role);
            ps.setString(2, playerUuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Error updating role: " + e.getMessage());
            return false;
        }
    }

    public boolean setTeamHome(int teamId, String locationStr) {
        String sql = "UPDATE `donut_teams` SET `home_location` = ? WHERE `id` = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, locationStr);
            ps.setInt(2, teamId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Error setting team home: " + e.getMessage());
            return false;
        }
    }

    public boolean delTeamHome(int teamId) {
        String sql = "UPDATE `donut_teams` SET `home_location` = NULL WHERE `id` = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, teamId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Error deleting team home: " + e.getMessage());
            return false;
        }
    }

    public boolean setTeamWarp(int teamId, String warpName, String locationStr, String password) {
        String sql = "INSERT INTO `donut_team_warps` (`team_id`, `warp_name`, `location`, `password`) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE `location` = VALUES(`location`), `password` = VALUES(`password`)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, teamId);
            ps.setString(2, warpName);
            ps.setString(3, locationStr);
            ps.setString(4, password);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Error setting team warp: " + e.getMessage());
            return false;
        }
    }

    public WarpRecord getTeamWarp(int teamId, String warpName) {
        String sql = "SELECT * FROM `donut_team_warps` WHERE `team_id` = ? AND LOWER(`warp_name`) = LOWER(?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, teamId);
            ps.setString(2, warpName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    WarpRecord w = new WarpRecord();
                    w.id = rs.getInt("id");
                    w.teamId = rs.getInt("team_id");
                    w.name = rs.getString("warp_name");
                    w.location = rs.getString("location");
                    w.password = rs.getString("password");
                    return w;
                }
            }
        } catch (SQLException e) {
            LOGGER.warning("Error getting team warp: " + e.getMessage());
        }
        return null;
    }

    public List<WarpRecord> listTeamWarps(int teamId) {
        List<WarpRecord> list = new ArrayList<>();
        String sql = "SELECT * FROM `donut_team_warps` WHERE `team_id` = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    WarpRecord w = new WarpRecord();
                    w.id = rs.getInt("id");
                    w.teamId = rs.getInt("team_id");
                    w.name = rs.getString("warp_name");
                    w.location = rs.getString("location");
                    w.password = rs.getString("password");
                    list.add(w);
                }
            }
        } catch (SQLException e) {
            LOGGER.warning("Error listing warps: " + e.getMessage());
        }
        return list;
    }

    public boolean delTeamWarp(int teamId, String warpName) {
        String sql = "DELETE FROM `donut_team_warps` WHERE `team_id` = ? AND LOWER(`warp_name`) = LOWER(?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, teamId);
            ps.setString(2, warpName);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Error deleting warp: " + e.getMessage());
            return false;
        }
    }

    public boolean depositBank(int teamId, double amount) {
        String sql = "UPDATE `donut_teams` SET `balance` = `balance` + ? WHERE `id` = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, amount);
            ps.setInt(2, teamId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Error depositing bank: " + e.getMessage());
            return false;
        }
    }

    public boolean withdrawBank(int teamId, double amount) {
        String sql = "UPDATE `donut_teams` SET `balance` = `balance` - ? WHERE `id` = ? AND `balance` >= ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, amount);
            ps.setInt(2, teamId);
            ps.setDouble(3, amount);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Error withdrawing bank: " + e.getMessage());
            return false;
        }
    }

    public boolean togglePvP(int teamId, boolean pvpEnabled) {
        String sql = "UPDATE `donut_teams` SET `pvp_enabled` = ? WHERE `id` = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, pvpEnabled);
            ps.setInt(2, teamId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Error toggling pvp: " + e.getMessage());
            return false;
        }
    }

    public List<TeamRecord> getTopTeams(int limit) {
        List<TeamRecord> list = new ArrayList<>();
        String sql = "SELECT * FROM `donut_teams` ORDER BY `points` DESC, `balance` DESC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapTeam(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.warning("Error fetching top teams: " + e.getMessage());
        }
        return list;
    }

    private TeamRecord mapTeam(ResultSet rs) throws SQLException {
        TeamRecord t = new TeamRecord();
        t.id = rs.getInt("id");
        t.name = rs.getString("name");
        t.tag = rs.getString("tag");
        try {
            t.ownerUuid = UUID.fromString(rs.getString("owner_uuid"));
        } catch (Exception ignored) {}
        t.homeLocation = rs.getString("home_location");
        t.pvpEnabled = rs.getBoolean("pvp_enabled");
        t.isPublic = rs.getBoolean("is_public");
        t.balance = rs.getDouble("balance");
        t.kills = rs.getInt("kills");
        t.deaths = rs.getInt("deaths");
        t.points = rs.getLong("points");
        t.tier = rs.getInt("tier");
        t.description = rs.getString("description");
        return t;
    }
}
