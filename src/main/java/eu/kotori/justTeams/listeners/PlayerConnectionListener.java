package eu.kotori.justTeams.listeners;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamManager;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerConnectionListener implements Listener {
   private final JustTeams plugin;
   private final TeamManager teamManager;

   public PlayerConnectionListener(JustTeams plugin) {
      this.plugin = plugin;
      this.teamManager = plugin.getTeamManager();
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      this.plugin.getTaskRunner().runAsync(() -> {
         this.plugin.getStorageManager().getStorage().cachePlayerName(player.getUniqueId(), player.getName());
         if (this.plugin.getConfigManager().isBedrockSupportEnabled() && this.plugin.getBedrockSupport().isBedrockPlayer(player)) {
            String gamertagx = this.plugin.getBedrockSupport().getBedrockGamertag(player);
            if (gamertagx != null && !gamertagx.equals(player.getName())) {
               this.plugin.getStorageManager().getStorage().cachePlayerName(player.getUniqueId(), gamertagx);
               this.plugin.getLogger().info("Cached Bedrock player: " + player.getName() + " (Gamertag: " + gamertagx + ")");
            }
         }

         if (this.plugin.getConfigManager().isCrossServerSyncEnabled()) {
            String serverIdentifier = this.plugin.getConfigManager().getServerIdentifier();
            this.plugin.getStorageManager().getStorage().updatePlayerSession(player.getUniqueId(), serverIdentifier);
         }
      });
      if (this.plugin.getConfigManager().isBedrockSupportEnabled() && this.plugin.getBedrockSupport().isBedrockPlayer(player)) {
         this.plugin.getLogger().info("Bedrock player joined: " + player.getName() + " (UUID: " + player.getUniqueId() + ")");
         if (this.plugin.getConfigManager().isShowGamertags()) {
            String gamertag = this.plugin.getBedrockSupport().getBedrockGamertag(player);
            if (gamertag != null && !gamertag.equals(player.getName())) {
               this.plugin.getLogger().info("Bedrock player gamertag: " + gamertag);
            }
         }
      }

      this.teamManager.handlePendingTeleport(player);
      this.teamManager.loadPlayerTeam(player);
      if (this.plugin.getTabHook() != null) {
         this.plugin.getTaskRunner().runTaskLater(() -> this.plugin.getTabHook().refreshTabPlayer(player), 40L);
      }

      this.plugin
         .getTaskRunner()
         .runAsyncTaskLater(
            () -> {
               List<Team> pendingInvites = this.teamManager.getPendingInvites(player.getUniqueId());
               if (!pendingInvites.isEmpty()) {
                  this.plugin
                     .getTaskRunner()
                     .runOnEntity(
                        player,
                        () -> {
                           for (Team team : pendingInvites) {
                              this.plugin
                                 .getMessageManager()
                                 .sendRawMessage(
                                    player,
                                    this.plugin.getMessageManager().getRawMessage("prefix")
                                       + this.plugin.getMessageManager().getRawMessage("invite_received").replace("<team>", team.getName())
                                 );
                           }

                           if (pendingInvites.size() == 1) {
                              this.plugin.getMessageManager().sendMessage(player, "pending_invites_singular");
                           } else {
                              this.plugin
                                 .getMessageManager()
                                 .sendRawMessage(
                                    player,
                                    this.plugin
                                       .getMessageManager()
                                       .getRawMessage("pending_invites_plural")
                                       .replace("<count>", String.valueOf(pendingInvites.size()))
                                 );
                           }
                        }
                     );
               }
            },
            40L
         );
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerQuit(PlayerQuitEvent event) {
      Player player = event.getPlayer();
      if (this.plugin.getConfigManager().isBedrockSupportEnabled()) {
         this.plugin.getBedrockSupport().clearPlayerCache(player.getUniqueId());
      }

      this.teamManager.unloadPlayer(player);
   }
}
