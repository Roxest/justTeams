package eu.kotori.justTeams;

import eu.kotori.justTeams.api.CustomDataManager;
import eu.kotori.justTeams.commands.TeamCommand;
import eu.kotori.justTeams.commands.TeamMessageCommand;
import eu.kotori.justTeams.config.ConfigManager;
import eu.kotori.justTeams.config.MessageManager;
import eu.kotori.justTeams.gui.GUIManager;
import eu.kotori.justTeams.gui.TeamGUIListener;
import eu.kotori.justTeams.hooks.EternalCombatHook;
import eu.kotori.justTeams.hooks.TabHook;
import eu.kotori.justTeams.hooks.UltimateKothHook;
import org.bstats.bukkit.Metrics;
import eu.kotori.justTeams.listeners.PlayerConnectionListener;
import eu.kotori.justTeams.listeners.PlayerStatsListener;
import eu.kotori.justTeams.listeners.PvPListener;
import eu.kotori.justTeams.listeners.TeamChatListener;
import eu.kotori.justTeams.listeners.TeamDamageBonusListener;
import eu.kotori.justTeams.listeners.TeamEnderChestListener;
import eu.kotori.justTeams.quests.QuestListener;
import eu.kotori.justTeams.quests.QuestManager;
import eu.kotori.justTeams.redis.RedisManager;
import eu.kotori.justTeams.storage.DatabaseMigrationManager;
import eu.kotori.justTeams.storage.DatabaseStorage;
import eu.kotori.justTeams.storage.StorageManager;
import eu.kotori.justTeams.team.GlowManager;
import eu.kotori.justTeams.team.TeamManager;
import eu.kotori.justTeams.team.TeamUpgradeManager;
import eu.kotori.justTeams.util.AliasManager;
import eu.kotori.justTeams.util.BedrockSupport;
import eu.kotori.justTeams.util.ChatInputManager;
import eu.kotori.justTeams.util.CommandManager;
import eu.kotori.justTeams.util.ConfigUpdater;
import eu.kotori.justTeams.util.DataRecoveryManager;
import eu.kotori.justTeams.util.DebugLogger;
import eu.kotori.justTeams.util.DiscordWebhookManager;
import eu.kotori.justTeams.util.FeatureRestrictionManager;
import eu.kotori.justTeams.util.GuiConfigManager;
import eu.kotori.justTeams.util.PAPIExpansion;
import eu.kotori.justTeams.util.StartupManager;
import eu.kotori.justTeams.util.StartupMessage;
import eu.kotori.justTeams.util.TaskRunner;
import eu.kotori.justTeams.util.VersionChecker;
import eu.kotori.justTeams.util.WebhookHelper;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class JustTeams extends JavaPlugin {
   private static JustTeams instance;
   private static NamespacedKey actionKey;
   private ConfigManager configManager;
   private MessageManager messageManager;
   private StorageManager storageManager;
   private RedisManager redisManager;
   private TeamManager teamManager;
   private QuestManager questManager;
   private TeamUpgradeManager teamUpgradeManager;
   private GlowManager glowManager;
   private GUIManager guiManager;
   private TaskRunner taskRunner;
   private ChatInputManager chatInputManager;
   private CommandManager commandManager;
   private AliasManager aliasManager;
   private GuiConfigManager guiConfigManager;
   private DebugLogger debugLogger;
   private StartupManager startupManager;
   private BedrockSupport bedrockSupport;
   private TeamChatListener teamChatListener;
   private DiscordWebhookManager webhookManager;
   private WebhookHelper webhookHelper;
   private MiniMessage miniMessage;
   private Economy economy;
   private Chat chat;
   private FeatureRestrictionManager featureRestrictionManager;
   private DataRecoveryManager dataRecoveryManager;
   private VersionChecker versionChecker;
   private UltimateKothHook ultimateKothHook;
   private EternalCombatHook eternalCombatHook;
   private TabHook tabHook;
   private CustomDataManager customDataManager;
   public boolean updateAvailable = false;
   public String latestVersion = "";
   public boolean packetEventsMissing = false;

   public void onEnable() {
      instance = this;
      Logger logger = this.getLogger();
      logger.info("Starting JustTeams...");
      actionKey = new NamespacedKey(this, "action");
      logger.info("Action key initialized");
      String serverName = Bukkit.getServer().getName();
      String serverNameLower = serverName.toLowerCase();
      logger.info("Detected server: " + serverName);
      if (serverName.equals("Folia")
         || serverNameLower.contains("folia")
         || serverNameLower.equals("canvas")
         || serverNameLower.equals("petal")
         || serverNameLower.equals("leaf")
         || serverNameLower.contains("luminol")) {
         logger.info("Folia-based server detected! Using threaded region scheduler support.");
      } else if (serverName.contains("Paper")
         || serverNameLower.contains("paper")
         || serverName.equals("Purpur")
         || serverName.equals("Airplane")
         || serverName.equals("Pufferfish")
         || serverNameLower.contains("universespigot")
         || serverNameLower.equals("plazma")
         || serverNameLower.equals("mirai")) {
         logger.info("Paper-based server detected. Using optimized scheduler.");
      } else if (serverName.equals("Spigot") || serverNameLower.contains("spigot")) {
         logger.info("Spigot-based server detected. Using standard Bukkit scheduler.");
      } else if (!serverName.equals("CraftBukkit") && !serverNameLower.contains("bukkit")) {
         logger.info("Generic Bukkit-compatible server detected: " + serverName);
      } else {
         logger.info("CraftBukkit server detected. Using standard Bukkit scheduler.");
      }

      this.miniMessage = MiniMessage.miniMessage();

      try {
         logger.info("Initializing managers...");
         this.initializeManagers();
         logger.info("Setting up economy integration...");
         this.setupEconomy();
         if (this.getConfig().getBoolean("bstats.enabled", true)) {
            int pluginId = 28485;
            new Metrics(this, pluginId);
            logger.info("bStats Metrics enabled.");
         } else {
            logger.info("bStats Metrics disabled in config.");
         }

         logger.info("Registering event listeners...");
         this.registerListeners();
         logger.info("Registering commands...");
         this.registerCommands();
         logger.info("Registering PlaceholderAPI...");
         this.registerPlaceholderAPI();
         logger.info("Starting cross-server tasks...");
         logger.info("Registering UltimateKoth integration...");
         this.registerUltimateKoth();
         logger.info("Registering EternalCombat integration...");
         this.registerEternalCombat();
         logger.info("Registering TAB integration...");
         this.registerTab();
         StartupMessage.send();
         logger.info("JustTeams has been enabled successfully!");
      } catch (Throwable e) {
         logger.severe("Failed to enable JustTeams: " + e.getMessage());
         logger.log(Level.SEVERE, "JustTeams enable error details", e);
         e.printStackTrace();
         this.getServer().getPluginManager().disablePlugin(this);
      }
   }

   public void onDisable() {
      Logger logger = this.getLogger();
      logger.info("Disabling JustTeams...");

      try {
         if (this.ultimateKothHook != null) {
            this.ultimateKothHook.unregisterGroupProvider();
         }
      } catch (Exception e) {
         logger.warning("Error unregistering UltimateKoth: " + e.getMessage());
      }

      try {
         if (this.eternalCombatHook != null) {
            this.eternalCombatHook.disable();
         }
      } catch (Exception e) {
         logger.warning("Error disabling EternalCombat hook: " + e.getMessage());
      }

      try {
         if (this.tabHook != null) {
            this.tabHook.disable();
         }
      } catch (Exception e) {
         logger.warning("Error disabling TAB hook: " + e.getMessage());
      }

      try {
         if (this.taskRunner != null) {
            this.taskRunner.cancelAllTasks();
         }
      } catch (Exception e) {
         logger.warning("Error cancelling tasks: " + e.getMessage());
      }

      try {
         if (this.teamManager != null) {
            this.teamManager.shutdown();
         }
      } catch (Exception e) {
         logger.warning("Error shutting down team manager: " + e.getMessage());
      }

      try {
         if (this.questManager != null) {
            this.questManager.shutdown();
         }
      } catch (Exception e) {
         logger.warning("Error shutting down quest manager: " + e.getMessage());
      }

      try {
         if (this.guiManager != null && this.guiManager.getUpdateThrottle() != null) {
            this.guiManager.getUpdateThrottle().cleanup();
         }
      } catch (Exception e) {
         logger.warning("Error cleaning up GUI throttles: " + e.getMessage());
      }

      try {
         if (this.storageManager != null) {
            this.storageManager.shutdown();
         }
      } catch (Exception e) {
         logger.warning("Error shutting down storage manager: " + e.getMessage());
      }

      try {
         if (this.webhookManager != null) {
            this.webhookManager.shutdown();
         }
      } catch (Exception e) {
         logger.warning("Error shutting down webhook manager: " + e.getMessage());
      }

      try {
         if (this.redisManager != null) {
            this.redisManager.shutdown();
         }
      } catch (Exception e) {
         logger.warning("Error shutting down Redis manager: " + e.getMessage());
      }

      logger.info("JustTeams has been disabled.");
   }

   private void initializeManagers() {
      this.configManager = new ConfigManager(this);
      ConfigUpdater.updateAllConfigs(this);
      ConfigUpdater.migrateToPlaceholderSystem(this);
      this.messageManager = new MessageManager(this);
      this.storageManager = new StorageManager(this);
      if (this.storageManager.init()) {
         this.redisManager = new RedisManager(this);
         if (this.configManager.isRedisEnabled()) {
            this.getLogger().info("Redis is enabled, initializing...");

            try {
               this.redisManager.initialize();
               this.getLogger().info("✓ Redis initialized successfully!");
            } catch (Exception e) {
               this.getLogger().warning("Failed to initialize Redis: " + e.getMessage());
               this.getLogger().warning("Falling back to MySQL-only mode (1-second polling)");
               e.printStackTrace();
            }
         } else {
            this.getLogger().info("Redis is disabled in config.yml, using MySQL-only mode (1-second polling)");
         }

         if (this.storageManager.getStorage() instanceof DatabaseStorage) {
            DatabaseMigrationManager migrationManager = new DatabaseMigrationManager(this, (DatabaseStorage)this.storageManager.getStorage());
            if (!migrationManager.performMigration()) {
               this.getLogger().warning("Database migration completed with warnings. Some features may not work correctly.");
            }
         }

         this.teamManager = new TeamManager(this);
         this.questManager = new QuestManager(this);
         this.teamUpgradeManager = new TeamUpgradeManager(this);
         this.guiManager = new GUIManager(this);
         this.taskRunner = new TaskRunner(this);
         this.chatInputManager = new ChatInputManager(this);
         this.commandManager = new CommandManager(this);
         this.aliasManager = new AliasManager(this);
         this.guiConfigManager = new GuiConfigManager(this);
         this.debugLogger = new DebugLogger(this);
         this.getLogger().info("Initializing BedrockSupport...");
         this.bedrockSupport = new BedrockSupport(this);
         this.getLogger().info("Initializing WebhookManager...");
         this.webhookManager = new DiscordWebhookManager(this);
         this.webhookHelper = new WebhookHelper(this);
         this.getLogger().info("Initializing FeatureRestrictionManager...");
         this.featureRestrictionManager = new FeatureRestrictionManager(this);
         this.getLogger().info("Initializing DataRecoveryManager...");
         this.dataRecoveryManager = new DataRecoveryManager(this);
         this.getLogger().info("Initializing VersionChecker...");
         this.versionChecker = new VersionChecker(this);
         this.versionChecker.check();
         this.customDataManager = new CustomDataManager(this.getLogger());
         if (this.getServer().getPluginManager().getPlugin("packetevents") != null) {
            this.glowManager = new GlowManager(this);
         }

         this.teamManager.cleanupEnderChestLocksOnStartup();
         if (this.storageManager.getStorage() instanceof DatabaseStorage) {
            this.startupManager = new StartupManager(this, (DatabaseStorage)this.storageManager.getStorage());
            if (!this.startupManager.performStartup()) {
               throw new RuntimeException("Startup sequence failed! Check logs for details.");
            }

            this.startupManager.schedulePeriodicHealthChecks();
            this.startupManager.schedulePeriodicPermissionSaves();
         }

         this.startCrossServerTasks();
      } else {
         this.getLogger().severe("═══════════════════════════════════════════════════════════");
         this.getLogger().severe("  DATABASE CONNECTION FAILED");
         this.getLogger().severe("═══════════════════════════════════════════════════════════");
         this.getLogger().severe("");
         this.getLogger().severe("  The plugin could not connect to the database.");
         this.getLogger().severe("");
         String storageType = this.getConfig().getString("storage.type", "unknown");
         if (!"mysql".equalsIgnoreCase(storageType) && !"mariadb".equalsIgnoreCase(storageType)) {
            this.getLogger().severe("  Storage type: " + storageType);
            this.getLogger().severe("  Check your config.yml storage settings");
         } else {
            this.getLogger().severe("  You are using MySQL/MariaDB storage.");
            this.getLogger().severe("  Please check:");
            this.getLogger().severe("    1. MySQL/MariaDB server is running");
            this.getLogger().severe("    2. Connection details in config.yml are correct");
            this.getLogger().severe("    3. Database exists and user has permissions");
            this.getLogger().severe("");
            this.getLogger().severe("  Or switch to H2 (local file storage):");
            this.getLogger().severe("    - Open config.yml");
            this.getLogger().severe("    - Change: storage.type: \"h2\"");
            this.getLogger().severe("    - Restart the server");
         }

         this.getLogger().severe("");
         this.getLogger().severe("═══════════════════════════════════════════════════════════");
         throw new RuntimeException("Failed to initialize storage manager - see above for details");
      }
   }

   private void startCrossServerTasks() {
      String serverName = this.configManager.getServerIdentifier();
      long heartbeatInterval = this.configManager.getHeartbeatInterval();
      long crossServerInterval = this.configManager.getCrossServerSyncInterval();
      long criticalInterval = this.configManager.getCriticalSyncInterval();
      long cacheCleanupInterval = this.configManager.getCacheCleanupInterval();
      this.taskRunner.runAsyncTaskTimer(() -> {
         try {
            if (this.storageManager.getStorage() instanceof DatabaseStorage dbStorage) {
               dbStorage.updateServerHeartbeat(serverName);
            } else {
               this.storageManager.getStorage().updateServerHeartbeat(serverName);
            }

            if (this.configManager.isDebugLoggingEnabled()) {
               this.debugLogger.log("Updated server heartbeat for: " + serverName);
            }
         } catch (Exception e) {
            this.getLogger().warning("Error updating server heartbeat: " + e.getMessage());
         }
      }, heartbeatInterval, heartbeatInterval);
      if (this.configManager.isCrossServerSyncEnabled()) {
         this.taskRunner.runAsyncTaskTimer(() -> {
            try {
               this.teamManager.syncCrossServerData();
               if (this.configManager.isDebugLoggingEnabled()) {
                  this.debugLogger.log("Cross-server sync cycle completed");
               }
            } catch (Exception e) {
               this.getLogger().warning("Error in cross-server sync: " + e.getMessage());
            }
         }, crossServerInterval, crossServerInterval);
         this.taskRunner.runAsyncTaskTimer(() -> {
            try {
               this.teamManager.syncCriticalUpdates();
            } catch (Exception e) {
               this.getLogger().warning("Error in critical sync: " + e.getMessage());
            }
         }, criticalInterval, criticalInterval);
         this.taskRunner.runAsyncTaskTimer(() -> {
            try {
               int processed = this.teamManager.processCrossServerMessages();
               if (processed > 0 && this.configManager.isDebugLoggingEnabled()) {
                  this.debugLogger.log("Processed " + processed + " cross-server chat messages");
               }
            } catch (Exception e) {
               this.getLogger().warning("Error processing cross-server messages: " + e.getMessage());
            }
         }, criticalInterval, criticalInterval);
         this.taskRunner.runAsyncTaskTimer(() -> {
            try {
               this.teamManager.flushCrossServerUpdates();
               if (this.configManager.isDebugLoggingEnabled()) {
                  this.debugLogger.log("Flushed pending cross-server updates");
               }
            } catch (Exception e) {
               this.getLogger().warning("Error flushing cross-server updates: " + e.getMessage());
            }
         }, 120L, 120L);
      }

      this.taskRunner.runAsyncTaskTimer(() -> {
         try {
            this.teamManager.cleanupExpiredCache();
            if (this.configManager.isDebugLoggingEnabled()) {
               this.debugLogger.log("Cleaned up expired cache entries");
            }
         } catch (Exception e) {
            this.getLogger().warning("Error cleaning up cache: " + e.getMessage());
         }
      }, cacheCleanupInterval, cacheCleanupInterval);
      if (this.configManager.isCrossServerSyncEnabled()) {
         this.taskRunner.runAsyncTaskTimer(() -> {
            try {
               this.storageManager.getStorage().cleanupStaleSessions(15);
               if (this.configManager.isDebugLoggingEnabled()) {
                  this.debugLogger.log("Cleaned up stale player sessions");
               }
            } catch (Exception e) {
               this.getLogger().warning("Error cleaning up stale sessions: " + e.getMessage());
            }
         }, 12000L, 12000L);
      }

      this.taskRunner.runAsyncTaskTimer(() -> {
         try {
            if (this.storageManager.getStorage() instanceof DatabaseStorage) {
               ((DatabaseStorage)this.storageManager.getStorage()).cleanupOldCrossServerData();
               if (this.configManager.isDebugLoggingEnabled()) {
                  this.debugLogger.log("Cleaned up old cross-server data");
               }
            }
         } catch (Exception e) {
            this.getLogger().warning("Error cleaning up old cross-server data: " + e.getMessage());
         }
      }, 1200L, 1200L);
      if (this.configManager.isConnectionPoolMonitoringEnabled()) {
         this.taskRunner.runAsyncTaskTimer(() -> {
            try {
               if (this.storageManager.getStorage() instanceof DatabaseStorage) {
                  DatabaseStorage dbStorage = (DatabaseStorage)this.storageManager.getStorage();
                  if (this.configManager.isDebugEnabled()) {
                     Map<String, Object> stats = dbStorage.getDatabaseStats();
                     this.debugLogger.log("Database stats: " + stats.toString());
                  }
               }
            } catch (Exception e) {
               this.getLogger().warning("Error monitoring connection pool: " + e.getMessage());
            }
         }, this.configManager.getConnectionPoolLogInterval() * 60L, this.configManager.getConnectionPoolLogInterval() * 60L);
      }
   }

   private void registerListeners() {
      this.getServer().getPluginManager().registerEvents(new TeamGUIListener(this), this);
      this.getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
      this.getServer().getPluginManager().registerEvents(new PlayerStatsListener(this), this);
      this.getServer().getPluginManager().registerEvents(new PvPListener(this), this);
      this.getServer().getPluginManager().registerEvents(new TeamDamageBonusListener(this), this);
      this.teamChatListener = new TeamChatListener(this);
      this.getServer().getPluginManager().registerEvents(this.teamChatListener, this);
      if (this.questManager != null) {
         this.questManager.load();
         if (this.questManager.isEnabled()) {
            this.getServer().getPluginManager().registerEvents(new QuestListener(this), this);
            this.questManager.startFlushTask();
            this.getLogger().info("Quest system enabled");
         }
      }

      if (this.configManager.isTeamEnderchestEnabled()) {
         this.getServer().getPluginManager().registerEvents(new TeamEnderChestListener(this), this);
         this.getLogger().info("Team Enderchest feature is enabled - listener registered");
      } else {
         this.getLogger().info("Team Enderchest feature is disabled - listener not registered");
      }

      if (this.getServer().getPluginManager().isPluginEnabled("UltimateKoth")) {
         try {
            this.ultimateKothHook = new UltimateKothHook(this);
            this.getServer().getPluginManager().registerEvents(this.ultimateKothHook, this);
            this.getLogger().info("UltimateKoth detected - listener registered");
         } catch (NoClassDefFoundError | Exception e) {
            this.getLogger().warning("Failed to register UltimateKoth hook: " + e.getMessage());
         }
      }

      if (this.getServer().getPluginManager().isPluginEnabled("EternalCombat")) {
         try {
            this.eternalCombatHook = new EternalCombatHook(this);
            this.getLogger().info("EternalCombat detected - hook created");
         } catch (NoClassDefFoundError | Exception e) {
            this.getLogger().warning("Failed to create EternalCombat hook: " + e.getMessage());
         }
      }
   }

   private void registerCommands() {
      TeamCommand teamCommand = new TeamCommand(this);
      TeamMessageCommand teamMessageCommand = new TeamMessageCommand(this);
      this.getCommand("team").setExecutor(teamCommand);
      this.getCommand("team").setTabCompleter(teamCommand);
      this.getCommand("teammsg").setExecutor(teamMessageCommand);
      this.getCommand("teammsg").setTabCompleter(teamMessageCommand);
      this.aliasManager.registerAliases(teamCommand, teamMessageCommand);
   }

   private void registerPlaceholderAPI() {
      if (this.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
         try {
            PAPIExpansion expansion = new PAPIExpansion(this);
            if (expansion.register()) {
               this.getLogger().info("✓ PlaceholderAPI expansion registered successfully!");
               this.getLogger().info("  Identifier: justteams");
               this.getLogger().info("  Test with: /papi parse me %justteams_tag%");
            } else {
               this.getLogger().warning("✗ Failed to register PlaceholderAPI expansion!");
               this.getLogger().warning("  This may cause placeholders to not work.");
            }
         } catch (Exception e) {
            this.getLogger().severe("✗ Error registering PlaceholderAPI expansion: " + e.getMessage());
            e.printStackTrace();
         }
      } else {
         this.getLogger().warning("PlaceholderAPI not found! Placeholders will not work.");
         this.getLogger().warning("Download from: https://www.spigotmc.org/resources/6245/");
      }
   }

   private void registerUltimateKoth() {
      if (this.ultimateKothHook != null) {
         this.taskRunner.runTaskLater(() -> this.ultimateKothHook.registerGroupProvider(), 20L);
      }
   }

   private void registerEternalCombat() {
      if (this.eternalCombatHook != null && this.getConfig().getBoolean("integrations.eternalcombat.enabled", true)) {
         this.taskRunner.runTaskLater(() -> this.eternalCombatHook.initialize().thenAccept(success -> {
            if (success) {
               this.getLogger().info("✓ EternalCombat integration enabled!");
            }
         }), 60L);
      }
   }

   private void registerTab() {
      this.tabHook = new TabHook(this);
      this.taskRunner.runTaskLater(() -> {
         this.tabHook.initialize();
         if (this.tabHook.isEnabled()) {
            for (Player online : this.getServer().getOnlinePlayers()) {
               this.tabHook.refreshTabPlayer(online);
            }
         }
      }, 40L);
   }

   public static JustTeams getInstance() {
      return instance;
   }

   public static NamespacedKey getActionKey() {
      return actionKey;
   }

   public ConfigManager getConfigManager() {
      return this.configManager;
   }

   public MessageManager getMessageManager() {
      return this.messageManager;
   }

   public StorageManager getStorageManager() {
      return this.storageManager;
   }

   public RedisManager getRedisManager() {
      return this.redisManager;
   }

   public TeamUpgradeManager getTeamUpgradeManager() {
      return this.teamUpgradeManager;
   }

   public TeamManager getTeamManager() {
      return this.teamManager;
   }

   public QuestManager getQuestManager() {
      return this.questManager;
   }

   public GlowManager getGlowManager() {
      return this.glowManager;
   }

   public TeamChatListener getTeamChatListener() {
      return this.teamChatListener;
   }

   public GUIManager getGuiManager() {
      return this.guiManager;
   }

   public TaskRunner getTaskRunner() {
      return this.taskRunner;
   }

   public ChatInputManager getChatInputManager() {
      return this.chatInputManager;
   }

   public CommandManager getCommandManager() {
      return this.commandManager;
   }

   public AliasManager getAliasManager() {
      return this.aliasManager;
   }

   public GuiConfigManager getGuiConfigManager() {
      return this.guiConfigManager;
   }

   public StartupManager getStartupManager() {
      return this.startupManager;
   }

   public DebugLogger getDebugLogger() {
      return this.debugLogger;
   }

   public FeatureRestrictionManager getFeatureRestrictionManager() {
      return this.featureRestrictionManager;
   }

   public DataRecoveryManager getDataRecoveryManager() {
      return this.dataRecoveryManager;
   }

   public DiscordWebhookManager getWebhookManager() {
      return this.webhookManager;
   }

   public WebhookHelper getWebhookHelper() {
      return this.webhookHelper;
   }

   public MiniMessage getMiniMessage() {
      return this.miniMessage;
   }

   public Economy getEconomy() {
      return this.economy;
   }

   public BedrockSupport getBedrockSupport() {
      return this.bedrockSupport;
   }

   public CustomDataManager getCustomDataManager() {
      return this.customDataManager;
   }

   public EternalCombatHook getEternalCombatHook() {
      return this.eternalCombatHook;
   }

   public TabHook getTabHook() {
      return this.tabHook;
   }

   private boolean setupEconomy() {
      if (this.getServer().getPluginManager().getPlugin("BoxChat") != null) {
         this.getLogger().info("BoxChat found! Using BoxChat API for chat messages.");
      }

      if (this.getServer().getPluginManager().getPlugin("packetevents") == null && this.getConfigManager().isTeamGlowEnabled()) {
         this.packetEventsMissing = true;
         StartupMessage.sendMissingPacketEventsWarning();
      }

      if (this.getServer().getPluginManager().getPlugin("Vault") == null) {
         this.getLogger().warning("Vault plugin not found! Economy features will be disabled.");
         return false;
      }

      RegisteredServiceProvider<Economy> rsp = this.getServer().getServicesManager().getRegistration(Economy.class);
      if (rsp == null) {
         this.getLogger().warning("No economy provider found! Economy features will be disabled.");
         return false;
      }

      this.economy = (Economy)rsp.getProvider();
      if (this.economy != null) {
         this.getLogger().info("Economy provider found: " + this.economy.getName());
      }

      RegisteredServiceProvider<Chat> chatProvider = this.getServer().getServicesManager().getRegistration(Chat.class);
      if (chatProvider != null) {
         this.chat = (Chat)chatProvider.getProvider();
         this.getLogger().info("Chat provider found: " + this.chat.getName() + " (prefix/suffix support enabled)");
      } else {
         this.getLogger().info("No chat provider found. Player prefixes will not be available.");
      }

      return this.economy != null;
   }

   public String getPlayerPrefix(Player player) {
      if (this.chat == null) {
         return "";
      }

      try {
         String prefix = this.chat.getPlayerPrefix(player);
         return prefix != null ? prefix : "";
      } catch (Exception e) {
         return "";
      }
   }

   public String getPlayerSuffix(Player player) {
      if (this.chat == null) {
         return "";
      }

      try {
         String suffix = this.chat.getPlayerSuffix(player);
         return suffix != null ? suffix : "";
      } catch (Exception e) {
         return "";
      }
   }
}
