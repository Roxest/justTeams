package eu.kotori.justTeams.listeners;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class PlayerStatsListener implements Listener {
   private final TeamManager teamManager;

   public PlayerStatsListener(JustTeams plugin) {
      this.teamManager = plugin.getTeamManager();
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerDeath(PlayerDeathEvent event) {
      Player victim = event.getEntity();
      Player killer = victim.getKiller();
      Team victimTeam = this.teamManager.getPlayerTeamCached(victim.getUniqueId());
      if (victimTeam != null) {
         victimTeam.incrementDeaths();
         JustTeams.getInstance().getTaskRunner().runAsync(() -> {
            try {
               JustTeams.getInstance().getStorageManager().getStorage().updateTeamStats(victimTeam.getId(), victimTeam.getKills(), victimTeam.getDeaths());
               if (JustTeams.getInstance().getConfigManager().isCrossServerSyncEnabled()) {
                  JustTeams.getInstance().getStorageManager().getStorage().addCrossServerUpdate(victimTeam.getId(), "TEAM_UPDATED", "", "ALL_SERVERS");
               }
            } catch (Exception e) {
               JustTeams.getInstance().getLogger().warning("Failed to update team stats: " + e.getMessage());
            }
         });
      }

      if (killer != null) {
         Team killerTeam = this.teamManager.getPlayerTeamCached(killer.getUniqueId());
         if (killerTeam != null && (victimTeam == null || killerTeam.getId() != victimTeam.getId())) {
            killerTeam.incrementKills();
            JustTeams.getInstance().getTaskRunner().runAsync(() -> {
               try {
                  JustTeams.getInstance().getStorageManager().getStorage().updateTeamStats(killerTeam.getId(), killerTeam.getKills(), killerTeam.getDeaths());
                  if (JustTeams.getInstance().getConfigManager().isCrossServerSyncEnabled()) {
                     JustTeams.getInstance().getStorageManager().getStorage().addCrossServerUpdate(killerTeam.getId(), "TEAM_UPDATED", "", "ALL_SERVERS");
                  }
               } catch (Exception e) {
                  JustTeams.getInstance().getLogger().warning("Failed to update team stats: " + e.getMessage());
               }
            });
         }
      }
   }
}
