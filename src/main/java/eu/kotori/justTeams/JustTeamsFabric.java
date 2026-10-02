package eu.kotori.justTeams;

import eu.kotori.justTeams.commands.FabricTeamCommand;
import eu.kotori.justTeams.commands.FabricTeamMessageCommand;
import eu.kotori.justTeams.config.FabricConfigManager;
import eu.kotori.justTeams.config.FabricMessageManager;
import eu.kotori.justTeams.gui.FabricGUIManager;
import eu.kotori.justTeams.hooks.FabricImpactorEconomyHook;
import eu.kotori.justTeams.hooks.FabricPlaceholderHook;
import eu.kotori.justTeams.listeners.FabricPlayerListener;
import eu.kotori.justTeams.listeners.FabricPvPListener;
import eu.kotori.justTeams.listeners.FabricStatsListener;
import eu.kotori.justTeams.storage.FabricStorageManager;
import eu.kotori.justTeams.team.FabricTeamManager;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class JustTeamsFabric implements ModInitializer, DedicatedServerModInitializer {

    private static JustTeamsFabric instance;
    private Logger logger = Logger.getLogger("JustTeams");
    private Path dataDirectory;
    private MiniMessage miniMessage;
    private FabricConfigManager configManager;
    private FabricMessageManager messageManager;
    private FabricGUIManager guiManager;
    private FabricStorageManager storageManager;
    private FabricTeamManager teamManager;
    private eu.kotori.justTeams.util.FabricChatInputManager chatInputManager;
    private net.minecraft.server.MinecraftServer server;

    public static JustTeamsFabric getInstance() {
        return instance;
    }

    public net.minecraft.server.MinecraftServer getServer() {
        return server;
    }

    public void setServer(net.minecraft.server.MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void onInitialize() {
        onInitializeServer();
    }

    @Override
    public void onInitializeServer() {
        if (instance != null) {
            return;
        }
        instance = this;
        logger.info("==================================================");
        logger.info("Initializing JustTeams 2.5.5 (Pure Fabric Mod)");
        logger.info("==================================================");

        this.dataDirectory = Paths.get("config", "JustTeams");
        File dir = this.dataDirectory.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        this.extractDefaultConfigs();
        this.miniMessage = MiniMessage.miniMessage();

        try {
            logger.info("Loading config.yml and messages.yml with SnakeYAML...");
            this.configManager = new FabricConfigManager(this);
            this.messageManager = new FabricMessageManager(this);

            logger.info("Initializing Fabric Storage & Database Pool...");
            this.storageManager = new FabricStorageManager(this);
            boolean dbSuccess = this.storageManager.init();
            if (dbSuccess) {
                logger.info("✓ Database initialized successfully!");
            } else {
                logger.severe("✗ Database initialization failed. Check your config.yml storage settings.");
            }

            this.teamManager = new FabricTeamManager(this, this.storageManager);

            this.chatInputManager = new eu.kotori.justTeams.util.FabricChatInputManager(this);

            logger.info("Initializing Fabric Server-Side GUI Engine (SGUI)...");
            this.guiManager = new FabricGUIManager(this);

            logger.info("Registering Economy Hook (Impactor Economy)...");
            FabricImpactorEconomyHook.isAvailable();

            logger.info("Registering Fabric Event Listeners...");
            FabricPlayerListener.register(this);
            FabricPvPListener.register(this);
            FabricStatsListener.register(this);

            logger.info("Registering Fabric Brigadier Commands...");
            FabricTeamCommand.register(this);
            FabricTeamMessageCommand.register(this);

            logger.info("Registering Fabric Placeholder API Hook...");
            FabricPlaceholderHook.register(this.teamManager);

            registerLifecycleHooks();

            logger.info("★ JustTeams Pure Fabric Mod 2.5.5 successfully initialized!");
        } catch (Exception e) {
            logger.severe("Failed to initialize JustTeams Fabric: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void reload() {
        if (configManager != null) configManager.load();
        if (messageManager != null) messageManager.load();
        if (guiManager != null) guiManager.loadConfig();
        if (storageManager != null) storageManager.init();
    }

    private void registerLifecycleHooks() {
        try {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(srv -> {
                this.server = srv;
                if (this.guiManager != null) {
                    this.guiManager.setServer(srv);
                }
                logger.info("✓ Attached MinecraftServer instance to JustTeams GUI Engine!");
            });
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
                if (storageManager != null) {
                    logger.info("Closing JustTeams database connection pool...");
                    storageManager.close();
                }
            });
            logger.info("✓ Registered Fabric server lifecycle hooks (STARTING / STOPPING)!");
        } catch (Throwable t) {
            registerShutdownHook();
        }
    }

    private void registerShutdownHook() {
        try {
            Class<?> lifecycleClass = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents");
            Field stoppingField = lifecycleClass.getField("SERVER_STOPPING");
            Object stoppingEvent = stoppingField.get(null);

            Class<?> listenerClass = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStarting");

            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (proxy, method, args) -> {
                    if (storageManager != null) {
                        logger.info("Closing JustTeams database connection pool...");
                        storageManager.close();
                    }
                    return null;
                }
            );

            FabricTeamCommand.invokeEventRegister(stoppingEvent, listener);
            logger.info("✓ Registered database shutdown hook!");
        } catch (Throwable ignored) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (storageManager != null) {
                    storageManager.close();
                }
            }));
        }
    }

    private void extractDefaultConfigs() {
        String[] configFiles = new String[] {
            "config.yml", "messages.yml", "gui.yml", "commands.yml", "quests.yml", "webhooks.yml", "placeholders.yml"
        };
        for (String fileName : configFiles) {
            File target = this.dataDirectory.resolve(fileName).toFile();
            if (!target.exists()) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(fileName)) {
                    if (in != null) {
                        Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        logger.info("Extracted default configuration: " + fileName);
                    }
                } catch (Exception e) {
                    logger.warning("Could not extract default " + fileName + ": " + e.getMessage());
                }
            }
        }
    }

    public Logger getLogger() {
        return logger;
    }

    public Path getDataFolder() {
        return dataDirectory;
    }

    public FabricConfigManager getConfigManager() {
        return configManager;
    }

    public FabricMessageManager getMessageManager() {
        return messageManager;
    }

    public FabricGUIManager getGuiManager() {
        return guiManager;
    }

    public FabricStorageManager getStorageManager() {
        return storageManager;
    }

    public FabricTeamManager getTeamManager() {
        return teamManager;
    }

    public eu.kotori.justTeams.util.FabricChatInputManager getChatInputManager() {
        return chatInputManager;
    }

    public MiniMessage getMiniMessage() {
        return miniMessage;
    }
}
