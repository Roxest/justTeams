package eu.kotori.justTeams.hooks;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class TabHook {
   private final JustTeams plugin;
   private boolean enabled;
   private Object tabApiInstance;
   private Method getPlayerMethod;
   private Method setPropertyMethod;

   public TabHook(JustTeams plugin) {
      this.plugin = plugin;
      this.enabled = false;
   }

   public void initialize() {
      if (Bukkit.getPluginManager().getPlugin("TAB") == null) {
         this.plugin.getLogger().info("TAB not found. TAB integration disabled.");
      } else {
         try {
            Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            Method getInstanceMethod = tabApiClass.getMethod("getInstance");
            this.tabApiInstance = getInstanceMethod.invoke(null);
            if (this.tabApiInstance == null) {
               this.plugin.getLogger().warning("TAB API instance is null. Integration disabled.");
               return;
            }

            this.getPlayerMethod = tabApiClass.getMethod("getPlayer", UUID.class);
            Class<?> tabPlayerClass = Class.forName("me.neznamy.tab.api.TabPlayer");
            this.setPropertyMethod = tabPlayerClass.getMethod("setProperty", String.class, String.class);
            this.enabled = true;
            this.plugin.getLogger().info("TAB integration enabled.");
         } catch (ClassNotFoundException | NoSuchMethodException e) {
            this.plugin.getLogger().warning("TAB API classes not found or version mismatch: " + e.getMessage());
         } catch (LinkageError e) {
            this.plugin.getLogger().warning("TAB linkage error: " + e.getMessage());
         } catch (Exception e) {
            this.plugin.getLogger().warning("Failed to initialize TAB hook: " + e.getClass().getSimpleName() + ": " + e.getMessage());
         }
      }
   }

   public void refreshTabPlayer(Player player) {
      if (this.enabled && player != null) {
         try {
            Object tabPlayer = this.getPlayerMethod.invoke(this.tabApiInstance, player.getUniqueId());
            if (tabPlayer == null) {
               return;
            }

            Team team = this.plugin.getTeamManager().getPlayerTeam(player.getUniqueId());
            String sortValue = this.buildSortValue(team);
            this.setPropertyMethod.invoke(tabPlayer, "justteams-team-id", sortValue);
         } catch (Exception e) {
            this.plugin.getLogger().warning("TAB refresh failed for " + player.getName() + ": " + e.getMessage());
         }
      }
   }

   public void clearTabPlayer(Player player) {
      if (this.enabled && player != null) {
         try {
            Object tabPlayer = this.getPlayerMethod.invoke(this.tabApiInstance, player.getUniqueId());
            if (tabPlayer == null) {
               return;
            }

            this.setPropertyMethod.invoke(tabPlayer, "justteams-team-id", "");
         } catch (Exception e) {
            this.plugin.getLogger().warning("TAB clear failed for " + player.getName() + ": " + e.getMessage());
         }
      }
   }

   public void disable() {
      if (this.enabled) {
         for (Player player : Bukkit.getOnlinePlayers()) {
            this.clearTabPlayer(player);
         }

         this.enabled = false;
         this.tabApiInstance = null;
         this.getPlayerMethod = null;
         this.setPropertyMethod = null;
         this.plugin.getLogger().info("TAB hook disabled.");
      }
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   private String buildSortValue(Team team) {
      return team == null ? "~noteam" : String.valueOf(team.getId());
   }
}
