package eu.kotori.justTeams.quests;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class QuestListener implements Listener {
   private final JustTeams plugin;
   private final Map<UUID, Long> distanceAccum = new ConcurrentHashMap<>();

   public QuestListener(JustTeams plugin) {
      this.plugin = plugin;
   }

   private Team teamOf(Player p) {
      return p == null ? null : this.plugin.getTeamManager().getPlayerTeamCached(p.getUniqueId());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onPlayerKill(PlayerDeathEvent event) {
      Player victim = event.getEntity();
      Player killer = victim.getKiller();
      if (killer != null && !killer.equals(victim)) {
         Team team = this.teamOf(killer);
         if (team != null) {
            if (!team.isMember(victim.getUniqueId())) {
               this.plugin.getQuestManager().onEvent(team.getId(), QuestType.KILL_PLAYERS, "ANY", 1L);
            }
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onMobKill(EntityDeathEvent event) {
      Player killer = event.getEntity().getKiller();
      if (killer != null) {
         if (!(event.getEntity() instanceof Player)) {
            Team team = this.teamOf(killer);
            if (team != null) {
               this.plugin.getQuestManager().onEvent(team.getId(), QuestType.KILL_MOBS, event.getEntityType().name(), 1L);
            }
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onBreak(BlockBreakEvent event) {
      Team team = this.teamOf(event.getPlayer());
      if (team != null) {
         this.plugin.getQuestManager().onEvent(team.getId(), QuestType.BREAK_BLOCKS, event.getBlock().getType().name(), 1L);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onPlace(BlockPlaceEvent event) {
      Team team = this.teamOf(event.getPlayer());
      if (team != null) {
         this.plugin.getQuestManager().onEvent(team.getId(), QuestType.PLACE_BLOCKS, event.getBlock().getType().name(), 1L);
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.distanceAccum.remove(event.getPlayer().getUniqueId());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onMove(PlayerMoveEvent event) {
      if (event.getFrom().getBlockX() != event.getTo().getBlockX() || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
         Player p = event.getPlayer();
         Team team = this.teamOf(p);
         if (team != null) {
            long accum = this.distanceAccum.merge(p.getUniqueId(), 1L, Long::sum);
            if (accum >= 10L) {
               this.distanceAccum.put(p.getUniqueId(), 0L);
               this.plugin.getQuestManager().onEvent(team.getId(), QuestType.TRAVEL_DISTANCE, "ANY", accum);
            }
         }
      }
   }
}
