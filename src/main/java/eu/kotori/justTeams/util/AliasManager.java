package eu.kotori.justTeams.util;

import eu.kotori.justTeams.JustTeams;
import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class AliasManager {
   private final JustTeams plugin;
   private FileConfiguration commandsConfig;
   private final List<Command> registeredAliases = new ArrayList<>();
   private CommandExecutor teamExecutor;
   private CommandExecutor teamMsgExecutor;

   public AliasManager(JustTeams plugin) {
      this.plugin = plugin;
      this.loadConfig();
   }

   private void loadConfig() {
      File commandsFile = new File(this.plugin.getDataFolder(), "commands.yml");
      if (!commandsFile.exists()) {
         this.plugin.saveResource("commands.yml", false);
      }

      this.commandsConfig = YamlConfiguration.loadConfiguration(commandsFile);
   }

   public void reload() {
      this.loadConfig();
      if (this.teamExecutor != null && this.teamMsgExecutor != null) {
         this.unregisterAliases();
         this.registerAliases(this.teamExecutor, this.teamMsgExecutor);
         this.syncCommandsToPlayers();
      }
   }

   public boolean isAliasEnabled(String alias) {
      return this.commandsConfig.getBoolean("command-aliases." + alias + ".enabled", true);
   }

   public void registerAliases(CommandExecutor teamExecutor, CommandExecutor teamMsgExecutor) {
      this.teamExecutor = teamExecutor;
      this.teamMsgExecutor = teamMsgExecutor;

      try {
         this.registerIfEnabled("guild", "g", "team", teamExecutor, (TabCompleter)teamExecutor);
         this.registerIfEnabled("clan", "c", "team", teamExecutor, (TabCompleter)teamExecutor);
         this.registerIfEnabled("party", null, "team", teamExecutor, (TabCompleter)teamExecutor);
         this.registerIfEnabled("guildmsg", "gmsg", "teammsg", teamMsgExecutor, null);
         this.registerIfEnabled("clanmsg", "cmsg", "teammsg", teamMsgExecutor, null);
         this.registerIfEnabled("partymsg", null, "teammsg", teamMsgExecutor, null);
      } catch (Exception e) {
         this.plugin.getLogger().log(Level.SEVERE, "Failed to register command aliases.", e);
      }
   }

   public void unregisterAliases() {
      if (!this.registeredAliases.isEmpty()) {
         try {
            CommandMap commandMap = this.getCommandMapReflectively();
            Map<String, Command> knownCommands = this.getKnownCommandsReflectively(commandMap);
            knownCommands.values().removeIf(this.registeredAliases::contains);

            for (Command command : this.registeredAliases) {
               command.unregister(commandMap);
            }
         } catch (Throwable t) {
            this.plugin.getLogger().warning("Failed to unregister command aliases: " + t.getMessage());
         } finally {
            this.registeredAliases.clear();
         }
      }
   }

   private void registerIfEnabled(String alias, String shortAlias, String targetCommand, CommandExecutor executor, TabCompleter completer) {
      if (this.isAliasEnabled(alias)) {
         this.registerAlias(alias, targetCommand, executor, completer);
         if (shortAlias != null) {
            this.registerAlias(shortAlias, targetCommand, executor, completer);
         }

         this.plugin.getLogger().info("Command alias /" + alias + " is enabled.");
      } else {
         this.plugin.getLogger().info("Command alias /" + alias + " is disabled (skipping registration).");
      }
   }

   private void registerAlias(String alias, String targetCommand, CommandExecutor executor, TabCompleter completer) {
      try {
         Constructor<PluginCommand> constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
         constructor.setAccessible(true);
         PluginCommand aliasCommand = constructor.newInstance(alias, this.plugin);
         aliasCommand.setExecutor(executor);
         if (completer != null) {
            aliasCommand.setTabCompleter(completer);
         }

         aliasCommand.setDescription("Alias for /" + targetCommand);
         this.getCommandMapReflectively().register(this.plugin.getName(), aliasCommand);
         this.registeredAliases.add(aliasCommand);
      } catch (Exception e) {
         this.plugin.getLogger().warning("Failed to register alias /" + alias + ": " + e.getMessage());
      }
   }

   private void syncCommandsToPlayers() {
      try {
         for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            player.updateCommands();
         }
      } catch (Throwable var3) {
      }
   }

   private CommandMap getCommandMapReflectively() throws Exception {
      java.lang.reflect.Method m = this.plugin.getServer().getClass().getMethod("getCommandMap");
      return (CommandMap)m.invoke(this.plugin.getServer());
   }

   @SuppressWarnings("unchecked")
   private Map<String, Command> getKnownCommandsReflectively(CommandMap commandMap) throws Exception {
      try {
         java.lang.reflect.Method m = commandMap.getClass().getMethod("getKnownCommands");
         return (Map<String, Command>)m.invoke(commandMap);
      } catch (NoSuchMethodException e) {
         java.lang.reflect.Field f = org.bukkit.command.SimpleCommandMap.class.getDeclaredField("knownCommands");
         f.setAccessible(true);
         return (Map<String, Command>)f.get(commandMap);
      }
   }
}
