package eu.kotori.justTeams.util;
import eu.kotori.justTeams.JustTeams;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.logging.Level;
public class AliasManager {
    private final JustTeams plugin;
    private FileConfiguration commandsConfig;
    public AliasManager(JustTeams plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    private void loadConfig() {
        File commandsFile = new File(plugin.getDataFolder(), "commands.yml");
        if (!commandsFile.exists()) {
            plugin.saveResource("commands.yml", false);
        }
        commandsConfig = YamlConfiguration.loadConfiguration(commandsFile);
    }
    public void reload() {
        loadConfig();
    }
    public void registerAliases() {
        try {
            registerAlias("guild", "team");
            registerAlias("clan", "team");
            registerAlias("party", "team");
            registerAlias("guildmsg", "teammsg");
            registerAlias("clanmsg", "teammsg");
            registerAlias("partymsg", "teammsg");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to dynamically register command aliases.", e);
        }
    }
    private void registerAlias(String alias, String targetCommand) {
        try {
            org.bukkit.command.Command target = plugin.getServer().getPluginCommand(targetCommand);
            if (target != null) {
                try {
                    java.lang.reflect.Constructor<org.bukkit.command.PluginCommand> constructor =
                        org.bukkit.command.PluginCommand.class.getDeclaredConstructor(String.class, org.bukkit.plugin.Plugin.class);
                    constructor.setAccessible(true);
                    org.bukkit.command.PluginCommand aliasCommand = constructor.newInstance(alias, plugin);
                    if (target instanceof org.bukkit.command.PluginCommand) {
                        org.bukkit.command.PluginCommand pluginTarget = (org.bukkit.command.PluginCommand) target;
                        aliasCommand.setExecutor(pluginTarget.getExecutor());
                        aliasCommand.setTabCompleter(pluginTarget.getTabCompleter());
                    }
                    aliasCommand.setDescription(target.getDescription());
                    aliasCommand.setUsage(target.getUsage());
                    Object commandMap = getCommandMap();
                    if (commandMap instanceof org.bukkit.command.CommandMap) {
                        ((org.bukkit.command.CommandMap) commandMap).register(plugin.getName(), aliasCommand);
                        plugin.getLogger().info("Registered command alias: /" + alias + " -> /" + targetCommand);
                    } else {
                        plugin.getLogger().warning("Could not find server CommandMap to register alias: " + alias);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to create alias command: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register alias " + alias + " for " + targetCommand + ": " + e.getMessage());
        }
    }

    private Object getCommandMap() {
        try {
            java.lang.reflect.Method method = plugin.getServer().getClass().getMethod("getCommandMap");
            return method.invoke(plugin.getServer());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to access command map: " + e.getMessage());
            return null;
        }
    }
}
