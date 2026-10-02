package eu.kotori.justTeams.storage;

import eu.kotori.justTeams.JustTeams;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class DatabaseMigrationManager {
   private final JustTeams plugin;
   private final DatabaseStorage databaseStorage;
   private static final int CURRENT_SCHEMA_VERSION = 5;

   public DatabaseMigrationManager(JustTeams plugin, DatabaseStorage databaseStorage) {
      this.plugin = plugin;
      this.databaseStorage = databaseStorage;
   }

   public boolean performMigration() {
      try {
         this.plugin.getLogger().info("Starting database migration process...");
         this.initializeSchemaVersion();
         int currentVersion = this.getCurrentSchemaVersion();
         this.plugin.getLogger().info("Current database schema version: " + currentVersion);
         if (currentVersion < 5) {
            this.plugin.getLogger().info("Database schema is outdated. Running migrations from version " + currentVersion + " to 5");
            if (!this.runMigrations(currentVersion)) {
               this.plugin.getLogger().severe("Database migration failed!");
               return false;
            }
         } else {
            this.plugin.getLogger().info("Database schema is up to date.");
         }

         if (!this.validateDatabaseIntegrity()) {
            this.plugin.getLogger().warning("Database integrity validation found issues, attempting to fix...");
            if (!this.repairDatabase()) {
               this.plugin.getLogger().severe("Database repair failed!");
               return false;
            }
         }

         this.plugin.getLogger().info("Database migration completed successfully!");
         return true;
      } catch (Exception e) {
         this.plugin.getLogger().log(Level.SEVERE, "Database migration failed with exception: " + e.getMessage(), e);
         return false;
      }
   }

   private void initializeSchemaVersion() throws SQLException {
      try (Connection conn = this.databaseStorage.getConnection()) {
         String createTableSQL = "CREATE TABLE IF NOT EXISTS schema_version (version INT PRIMARY KEY, applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, description VARCHAR(255))";

         try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
         }

         String checkVersionSQL = "SELECT COUNT(*) FROM schema_version";

         try (
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(checkVersionSQL);
         ) {
            if (rs.next() && rs.getInt(1) == 0) {
               String insertVersionSQL = "INSERT INTO schema_version (version, description) VALUES (1, 'Initial schema version')";

               try (Statement insertStmt = conn.createStatement()) {
                  insertStmt.execute(insertVersionSQL);
               }
            }
         }
      }
   }

   private int getCurrentSchemaVersion() {
      try (Connection conn = this.databaseStorage.getConnection()) {
         if (conn == null || conn.isClosed()) {
            this.plugin.getLogger().warning("Database connection is null or closed");
            return 1;
         }

         String sql = "SELECT MAX(version) FROM schema_version";

         try (
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
         ) {
            if (rs.next()) {
               return rs.getInt(1);
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not get schema version, assuming version 1: " + e.getMessage());
      }

      return 1;
   }

   private boolean runMigrations(int fromVersion) {
      try {
         for (DatabaseMigrationManager.Migration migration : this.getMigrations()) {
            if (migration.getVersion() > fromVersion && migration.getVersion() <= 5) {
               this.plugin.getLogger().info("Running migration " + migration.getVersion() + ": " + migration.getDescription());
               if (!migration.execute(this.databaseStorage)) {
                  this.plugin.getLogger().severe("Migration " + migration.getVersion() + " failed!");
                  return false;
               }

               this.recordMigration(migration.getVersion(), migration.getDescription());
            }
         }

         return true;
      } catch (Exception e) {
         this.plugin.getLogger().log(Level.SEVERE, "Migration execution failed: " + e.getMessage(), e);
         return false;
      }
   }

   private void recordMigration(int version, String description) throws SQLException {
      try (Connection conn = this.databaseStorage.getConnection()) {
         String sql = "INSERT INTO schema_version (version, description) VALUES (?, ?)";

         try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, version);
            stmt.setString(2, description);
            stmt.executeUpdate();
         }
      }
   }

   private List<DatabaseMigrationManager.Migration> getMigrations() {
      List<DatabaseMigrationManager.Migration> migrations = new ArrayList<>();
      migrations.add(new DatabaseMigrationManager.Migration(2, "Add member permission columns", this::migration2_AddMemberPermissions));
      migrations.add(new DatabaseMigrationManager.Migration(3, "Add blacklist table and fix column issues", this::migration3_AddBlacklistAndFixColumns));
      migrations.add(new DatabaseMigrationManager.Migration(4, "Add cross-server tables and missing features", this::migration4_AddCrossServerTables));
      migrations.add(
         new DatabaseMigrationManager.Migration(
            5, "Add tier column to teams and can_promote_to_co_owner to members", this::migration5_AddTierAndPromoteCoOwnerPermission
         )
      );
      return migrations;
   }

   private boolean migration5_AddTierAndPromoteCoOwnerPermission(DatabaseStorage storage) {
      try {
         if (!this.hasColumn("donut_teams", "tier")) {
            this.addColumnSafely("donut_teams", "tier", "INT DEFAULT 1");
         }

         if (!this.hasColumn("donut_team_members", "can_promote_to_co_owner")) {
            this.addColumnSafely("donut_team_members", "can_promote_to_co_owner", "BOOLEAN DEFAULT FALSE");
         }

         return true;
      } catch (Exception e) {
         this.plugin.getLogger().log(Level.SEVERE, "Migration 5 failed: " + e.getMessage(), e);
         return false;
      }
   }

   private boolean migration2_AddMemberPermissions(DatabaseStorage storage) {
      try {
         String[] columns = new String[]{"can_edit_members", "can_edit_co_owners", "can_kick_members", "can_promote_members", "can_demote_members"};

         for (String column : columns) {
            if (!this.hasColumn("donut_team_members", column)) {
               this.addColumnSafely("donut_team_members", column, "BOOLEAN DEFAULT false");
            }
         }

         this.updateExistingMemberPermissions();
         return true;
      } catch (Exception e) {
         this.plugin.getLogger().log(Level.SEVERE, "Migration 2 failed: " + e.getMessage(), e);
         return false;
      }
   }

   private boolean migration3_AddBlacklistAndFixColumns(DatabaseStorage storage) {
      try {
         this.createBlacklistTable();
         String[] teamColumns = new String[]{"is_public"};

         for (String column : teamColumns) {
            if (!this.hasColumn("donut_teams", column)) {
               this.addColumnSafely("donut_teams", column, "BOOLEAN DEFAULT false");
            }
         }

         return true;
      } catch (Exception e) {
         this.plugin.getLogger().log(Level.SEVERE, "Migration 3 failed: " + e.getMessage(), e);
         return false;
      }
   }

   private boolean migration4_AddCrossServerTables(DatabaseStorage storage) {
      try {
         this.createCrossServerTables();
         return true;
      } catch (Exception e) {
         this.plugin.getLogger().log(Level.SEVERE, "Migration 4 failed: " + e.getMessage(), e);
         return false;
      }
   }

   private void createCrossServerTables() throws SQLException {
      try (Connection conn = this.databaseStorage.getConnection()) {
         String createUpdatesTable = "CREATE TABLE IF NOT EXISTS donut_cross_server_updates (id INT AUTO_INCREMENT PRIMARY KEY, team_id INT NOT NULL, update_type VARCHAR(50) NOT NULL, player_uuid VARCHAR(36) NOT NULL, server_name VARCHAR(64) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (team_id) REFERENCES donut_teams(id) ON DELETE CASCADE)";

         try (Statement stmt = conn.createStatement()) {
            stmt.execute(createUpdatesTable);
         }

         String createMessagesTable = "CREATE TABLE IF NOT EXISTS donut_cross_server_messages (id INT AUTO_INCREMENT PRIMARY KEY, team_id INT NOT NULL, player_uuid VARCHAR(36) NOT NULL, message TEXT NOT NULL, server_name VARCHAR(64) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (team_id) REFERENCES donut_teams(id) ON DELETE CASCADE)";

         try (Statement stmt = conn.createStatement()) {
            stmt.execute(createMessagesTable);
         }

         String createHomesTable = "CREATE TABLE IF NOT EXISTS donut_team_homes (team_id INT PRIMARY KEY, location VARCHAR(255) NOT NULL, server_name VARCHAR(64) NOT NULL, FOREIGN KEY (team_id) REFERENCES donut_teams(id) ON DELETE CASCADE)";

         try (Statement stmt = conn.createStatement()) {
            stmt.execute(createHomesTable);
         }

         String createWarpsTable = "CREATE TABLE IF NOT EXISTS donut_team_warps (id INT AUTO_INCREMENT PRIMARY KEY, team_id INT NOT NULL, warp_name VARCHAR(32) NOT NULL, location VARCHAR(255) NOT NULL, server_name VARCHAR(64) NOT NULL, password VARCHAR(64), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY team_warp (team_id, warp_name), FOREIGN KEY (team_id) REFERENCES donut_teams(id) ON DELETE CASCADE)";

         try (Statement stmt = conn.createStatement()) {
            stmt.execute(createWarpsTable);
         }

         String createJoinRequestsTable = "CREATE TABLE IF NOT EXISTS donut_join_requests (id INT AUTO_INCREMENT PRIMARY KEY, team_id INT NOT NULL, player_uuid VARCHAR(36) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY team_player (team_id, player_uuid), FOREIGN KEY (team_id) REFERENCES donut_teams(id) ON DELETE CASCADE)";

         try (Statement stmt = conn.createStatement()) {
            stmt.execute(createJoinRequestsTable);
         }

         String createEnderChestLocksTable = "CREATE TABLE IF NOT EXISTS donut_ender_chest_locks (team_id INT PRIMARY KEY, server_identifier VARCHAR(255) NOT NULL, lock_time TIMESTAMP NOT NULL, FOREIGN KEY (team_id) REFERENCES donut_teams(id) ON DELETE CASCADE)";

         try (Statement stmt = conn.createStatement()) {
            stmt.execute(createEnderChestLocksTable);
         }

         String createTeamLocksTable = "CREATE TABLE IF NOT EXISTS donut_team_locks (team_id INT PRIMARY KEY, server_identifier VARCHAR(255) NOT NULL, lock_time TIMESTAMP NOT NULL, FOREIGN KEY (team_id) REFERENCES donut_teams(id) ON DELETE CASCADE)";

         try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTeamLocksTable);
         }

         this.plugin.getLogger().info("Cross-server tables created/verified successfully.");
      }
   }

   private void addColumnSafely(String tableName, String columnName, String columnDefinition) throws SQLException {
      try (Connection conn = this.databaseStorage.getConnection()) {
         String sql = "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition;

         try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            this.plugin.getLogger().info("Added column " + columnName + " to " + tableName);
         }
      } catch (SQLException e) {
         if (e.getErrorCode() != 42121
            && e.getErrorCode() != 1060
            && !"42S21".equals(e.getSQLState())
            && !e.getMessage().toLowerCase().contains("already exists")
            && !e.getMessage().toLowerCase().contains("duplicate column")) {
            throw e;
         }

         this.plugin.getLogger().info("Column " + columnName + " already exists in " + tableName + ", skipping.");
      }
   }

   private boolean hasColumn(String tableName, String columnName) throws SQLException {
      try (Connection conn = this.databaseStorage.getConnection()) {
         DatabaseMetaData md = conn.getMetaData();

         try (ResultSet rs = md.getColumns(null, null, tableName, columnName)) {
            if (rs.next()) {
               return true;
            }
         }

         try (ResultSet rs = md.getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
            return rs.next();
         }
      }
   }

   private void updateExistingMemberPermissions() {
      try (Connection conn = this.databaseStorage.getConnection()) {
         String sql = "UPDATE donut_team_members SET can_edit_members = false, can_edit_co_owners = false, can_kick_members = false, can_promote_members = false, can_demote_members = false WHERE can_edit_members IS NULL OR can_edit_co_owners IS NULL";

         try (Statement stmt = conn.createStatement()) {
            int updated = stmt.executeUpdate(sql);
            if (updated > 0) {
               this.plugin.getLogger().info("Updated " + updated + " member permission records with default values.");
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not update existing member permissions: " + e.getMessage());
      }
   }

   private void createBlacklistTable() {
      try (Connection conn = this.databaseStorage.getConnection()) {
         String createTableSQL = "CREATE TABLE IF NOT EXISTS donut_team_blacklist (id INT AUTO_INCREMENT PRIMARY KEY, team_id INT NOT NULL, player_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(16) NOT NULL, reason TEXT, blacklisted_by_uuid VARCHAR(36) NOT NULL, blacklisted_by_name VARCHAR(16) NOT NULL, blacklisted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(team_id, player_uuid), FOREIGN KEY (team_id) REFERENCES donut_teams(id) ON DELETE CASCADE)";

         try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
            this.plugin.getLogger().info("Blacklist table created/verified successfully.");
         }
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not create blacklist table: " + e.getMessage());
      }
   }

   private boolean validateDatabaseIntegrity() {
      try {
         this.plugin.getLogger().info("Validating database integrity...");
         String[] requiredTables = new String[]{
            "donut_teams",
            "donut_team_members",
            "donut_team_homes",
            "donut_team_warps",
            "donut_join_requests",
            "donut_servers",
            "donut_pending_teleports",
            "donut_ender_chest_locks",
            "donut_cross_server_updates",
            "donut_cross_server_messages",
            "donut_team_blacklist",
            "donut_team_locks",
            "schema_version"
         };

         for (String table : requiredTables) {
            if (!this.tableExists(table)) {
               this.plugin.getLogger().warning("Required table " + table + " is missing!");
               return false;
            }
         }

         if (!this.validateTableColumns()) {
            return false;
         }

         this.plugin.getLogger().info("Database integrity validation passed.");
         return true;
      } catch (Exception e) {
         this.plugin.getLogger().log(Level.WARNING, "Database integrity validation failed: " + e.getMessage(), e);
         return false;
      }
   }

   private boolean tableExists(String tableName) throws SQLException {
      try (Connection conn = this.databaseStorage.getConnection()) {
         DatabaseMetaData md = conn.getMetaData();

         try (ResultSet rs = md.getTables(null, null, tableName, null)) {
            if (rs.next()) {
               return true;
            }
         }

         try (ResultSet rs = md.getTables(null, null, tableName.toUpperCase(), null)) {
            return rs.next();
         }
      }
   }

   private boolean validateTableColumns() throws SQLException {
      String[] teamColumns = new String[]{"id", "name", "tag", "owner_uuid", "pvp_enabled", "is_public", "balance", "kills", "deaths"};

      for (String column : teamColumns) {
         if (!this.hasColumn("donut_teams", column)) {
            this.plugin.getLogger().warning("Required column " + column + " is missing from donut_teams table!");
            return false;
         }
      }

      String[] memberColumns = new String[]{
         "player_uuid", "team_id", "role", "join_date", "can_withdraw", "can_use_enderchest", "can_set_home", "can_use_home"
      };

      for (String column : memberColumns) {
         if (!this.hasColumn("donut_team_members", column)) {
            this.plugin.getLogger().warning("Required column " + column + " is missing from donut_team_members table!");
            return false;
         }
      }

      return true;
   }

   private boolean repairDatabase() {
      try {
         this.plugin.getLogger().info("Attempting to repair database...");
         if (!this.tableExists("donut_teams")) {
            this.plugin.getLogger().info("Recreating donut_teams table...");
         }

         if (!this.hasColumn("donut_teams", "is_public")) {
            this.plugin.getLogger().info("Adding missing is_public column...");
            this.addColumnSafely("donut_teams", "is_public", "BOOLEAN DEFAULT false");
         }

         this.plugin.getLogger().info("Database repair completed.");
         return true;
      } catch (Exception e) {
         this.plugin.getLogger().log(Level.SEVERE, "Database repair failed: " + e.getMessage(), e);
         return false;
      }
   }

   private static class Migration {
      private final int version;
      private final String description;
      private final DatabaseMigrationManager.MigrationExecutor executor;

      public Migration(int version, String description, DatabaseMigrationManager.MigrationExecutor executor) {
         this.version = version;
         this.description = description;
         this.executor = executor;
      }

      public int getVersion() {
         return this.version;
      }

      public String getDescription() {
         return this.description;
      }

      public boolean execute(DatabaseStorage storage) throws Exception {
         return this.executor.execute(storage);
      }
   }

   private interface MigrationExecutor {
      boolean execute(DatabaseStorage var1) throws Exception;
   }
}
