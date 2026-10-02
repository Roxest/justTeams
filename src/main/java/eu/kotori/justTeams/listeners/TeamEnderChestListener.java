package eu.kotori.justTeams.listeners;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.util.InventoryUtil;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class TeamEnderChestListener implements Listener {
   private final JustTeams plugin;
   private final ConcurrentHashMap<UUID, Long> lastUpdateTime = new ConcurrentHashMap<>();
   private static final long UPDATE_COOLDOWN = 100L;
   private static final long SLOT_UPDATE_COOLDOWN = 50L;

   public TeamEnderChestListener(JustTeams plugin) {
      this.plugin = plugin;
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onInventoryOpen(InventoryOpenEvent event) {
      if (event.getInventory().getHolder() instanceof Team team) {
         Player var4 = (Player)event.getPlayer();
         team.addEnderChestViewer(var4.getUniqueId());
         if (this.plugin.getConfigManager().isDebugEnabled()) {
            this.plugin
               .getLogger()
               .info(
                  "Player " + var4.getName() + " opened team enderchest for team " + team.getName() + " (viewers: " + team.getEnderChestViewers().size() + ")"
               );
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getInventory().getHolder() instanceof Team team) {
         Player var6 = (Player)event.getWhoClicked();
         long currentTime = System.currentTimeMillis();
         if (!this.lastUpdateTime.containsKey(var6.getUniqueId()) || currentTime - this.lastUpdateTime.get(var6.getUniqueId()) >= 50L) {
            this.handleInventoryChange(team, var6, event.getInventory(), "click");
            this.lastUpdateTime.put(var6.getUniqueId(), currentTime);
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onInventoryDrag(InventoryDragEvent event) {
      if (event.getInventory().getHolder() instanceof Team team) {
         Player var6 = (Player)event.getWhoClicked();
         long currentTime = System.currentTimeMillis();
         if (!this.lastUpdateTime.containsKey(var6.getUniqueId()) || currentTime - this.lastUpdateTime.get(var6.getUniqueId()) >= 50L) {
            this.handleInventoryChange(team, var6, event.getInventory(), "drag");
            this.lastUpdateTime.put(var6.getUniqueId(), currentTime);
         }
      }
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent event) {
      this.lastUpdateTime.remove(event.getPlayer().getUniqueId());
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onInventoryClose(InventoryCloseEvent event) {
      if (event.getInventory().getHolder() instanceof Team team) {
         Player var4 = (Player)event.getPlayer();
         team.removeEnderChestViewer(var4.getUniqueId());
         if (this.plugin.getConfigManager().isDebugEnabled()) {
            this.plugin
               .getLogger()
               .info(
                  "Player "
                     + var4.getName()
                     + " closed team enderchest for team "
                     + team.getName()
                     + " (remaining viewers: "
                     + team.getEnderChestViewers().size()
                     + ")"
               );
         }

         if (!team.hasEnderChestViewers()) {
            // 1. Serialize on the main thread
            String serializedData = null;
            try {
               if (team.getEnderChest() != null) {
                  serializedData = InventoryUtil.serializeInventory(team.getEnderChest());
               }
            } catch (Exception e) {
               this.plugin.getLogger().warning("Failed to serialize enderchest: " + e.getMessage());
            }

            final String dataToSave = serializedData;

            // 2. Jump to Async for the database save
            this.plugin.getTaskRunner().runAsync(() -> {
               try {
                  this.plugin.getTeamManager().saveAndReleaseEnderChest(team, dataToSave);
                  if (this.plugin.getConfigManager().isDebugEnabled()) {
                     this.plugin.getLogger().info("✓ Last viewer closed enderchest for team " + team.getName() + ", saved and released lock");
                  }
               } catch (Exception e) {
                  this.plugin.getLogger().warning("Error saving enderchest on close for " + var4.getName() + ": " + e.getMessage());
                  e.printStackTrace();
               }
            });
         }
      }
   }

   private void handleInventoryChange(Team team, Player player, Inventory inventory, String changeType) {
      this.plugin.getTaskRunner().run(() -> {
         Inventory current = team.getEnderChest();
         if (current != null) {
            ItemStack[] live = current.getContents();
            ItemStack[] snapshot = new ItemStack[live.length];

            for (int i = 0; i < live.length; i++) {
               snapshot[i] = live[i] == null ? null : live[i].clone();
            }

            this.plugin.getTaskRunner().runAsync(() -> {
               try {
                  this.plugin.getTeamManager().saveEnderChestSnapshot(team, snapshot);
               } catch (Exception e) {
                  this.plugin.getLogger().warning("Error handling enderchest change for " + player.getName() + ": " + e.getMessage());
               }
            });
            if (team.hasEnderChestViewers()) {
               this.notifyOtherViewers(team, player, changeType);
            }
         }
      });
   }

   private void notifyOtherViewers(Team team, Player changer, String changeType) {
      for (UUID viewerUuid : team.getEnderChestViewers()) {
         if (!viewerUuid.equals(changer.getUniqueId())) {
            Player viewer = Bukkit.getPlayer(viewerUuid);
            if (viewer != null && viewer.isOnline()) {
               try {
                  this.refreshViewerInventory(viewer, team);
               } catch (Exception e) {
                  this.plugin.getLogger().warning("Failed to refresh enderchest for viewer " + viewer.getName() + ": " + e.getMessage());
               }
            }
         }
      }
   }

   private void refreshViewerInventory(Player viewer, Team team) {
      if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof Team) {
         this.plugin.getTaskRunner().runOnEntity(viewer, () -> {
            try {
               viewer.closeInventory();
               this.plugin.getTaskRunner().runOnEntity(viewer, () -> viewer.openInventory(team.getEnderChest()));
            } catch (Exception e) {
               this.plugin.getLogger().warning("Failed to refresh enderchest inventory for " + viewer.getName() + ": " + e.getMessage());
            }
         });
      }
   }
}
