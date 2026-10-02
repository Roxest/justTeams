package eu.kotori.justTeams.team;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.CollisionRule;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.NameTagVisibility;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.OptionData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import eu.kotori.justTeams.JustTeams;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class GlowManager implements Listener {
   private final JustTeams plugin;
   private boolean enabled;
   private boolean usePacketEvents;
   private final boolean onlyShowOwnTeam;
   private final Map<UUID, Map<UUID, ChatColor>> glowingCache = new ConcurrentHashMap<>();
   private final Set<UUID> colorTeamsCreated = ConcurrentHashMap.newKeySet();
   private final ConcurrentHashMap<Integer, UUID> entityIdToUuid = new ConcurrentHashMap<>();

   public GlowManager(JustTeams plugin) {
      this.plugin = plugin;
      this.enabled = plugin.getConfig().getBoolean("features.team_glow", true);
      this.onlyShowOwnTeam = plugin.getConfig().getBoolean("settings.glow.only_show_own_team", true);
      if (this.enabled) {
         if (plugin.getServer().getPluginManager().getPlugin("packetevents") != null) {
            this.usePacketEvents = true;
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            new PacketEventsGlowHandler(this).register();
            this.startRangeCheckTask();

            for (Player p : Bukkit.getOnlinePlayers()) {
               this.createColorTeams(p);
               plugin.getTaskRunner().runTaskLater(() -> this.refreshGlow(p), 20L);
            }

            plugin.getLogger().info("Team Glow enabled using PacketEvents.");
         } else {
            plugin.getLogger().warning("PacketEvents not found! Team Glow has been disabled.");
            this.enabled = false;
            this.usePacketEvents = false;
         }
      }
   }

   public void setGlow(Player target, Player receiver, ChatColor color) {
      if (this.enabled && this.usePacketEvents) {
         try {
            ChatColor previousColor = null;
            if (this.glowingCache.containsKey(receiver.getUniqueId())) {
               Map<UUID, ChatColor> targets = this.glowingCache.get(receiver.getUniqueId());
               previousColor = targets.get(target.getUniqueId());
               if (previousColor == color) {
                  return;
               }
            }

            if (previousColor != null && previousColor != color) {
               this.sendTeamRemovePacket(target, receiver, previousColor);
            }

            this.sendTeamPacket(target, receiver, color);
            this.sendMetadataPacket(target, receiver, true);
            this.glowingCache.computeIfAbsent(receiver.getUniqueId(), k -> new ConcurrentHashMap<>()).put(target.getUniqueId(), color);
            if (this.plugin.getConfigManager().isDebugEnabled()) {
               this.plugin
                  .getLogger()
                  .info("[GlowDebug] SENT glow packets: Target=" + target.getName() + " Receiver=" + receiver.getName() + " Color=" + color.name());
            }
         } catch (Exception e) {
            if (this.plugin.getConfigManager().isDebugEnabled()) {
               this.plugin.getLogger().warning("[GlowDebug] Failed to set glow: " + e.getMessage());
               e.printStackTrace();
            }
         }
      }
   }

   public void unsetGlow(Player target, Player receiver) {
      if (this.enabled && this.usePacketEvents) {
         try {
            ChatColor previousColor = null;
            if (this.glowingCache.containsKey(receiver.getUniqueId())) {
               previousColor = this.glowingCache.get(receiver.getUniqueId()).remove(target.getUniqueId());
            }

            if (previousColor != null) {
               this.sendTeamRemovePacket(target, receiver, previousColor);
            }

            this.sendMetadataPacket(target, receiver, false);
         } catch (Exception var4) {
         }
      }
   }

   private void sendTeamRemovePacket(Player target, Player receiver, ChatColor color) {
      String teamName = "JT_" + color.name();
      if (teamName.length() > 16) {
         teamName = teamName.substring(0, 16);
      }

      WrapperPlayServerTeams packet = new WrapperPlayServerTeams(
         teamName, TeamMode.REMOVE_ENTITIES, (ScoreBoardTeamInfo)null, Collections.singletonList(target.getName())
      );
      PacketEvents.getAPI().getPlayerManager().sendPacket(receiver, packet);
   }

   private void sendMetadataPacket(Player target, Player receiver, boolean glowing) {
      byte status = 0;
      if (target.getFireTicks() > 0) {
         status = (byte)(status | 1);
      }

      if (target.isSneaking()) {
         status = (byte)(status | 2);
      }

      if (target.isSprinting()) {
         status = (byte)(status | 8);
      }

      if (target.isSwimming()) {
         status = (byte)(status | 16);
      }

      if (target.isInvisible()) {
         status = (byte)(status | 32);
      }

      if (glowing) {
         status = (byte)(status | 64);
      }

      if (target.isGliding()) {
         status = (byte)(status | 128);
      }

      EntityData entityData = new EntityData(0, EntityDataTypes.BYTE, status);
      WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(target.getEntityId(), Collections.singletonList(entityData));
      PacketEvents.getAPI().getPlayerManager().sendPacket(receiver, packet);
   }

   private void sendTeamPacket(Player target, Player receiver, ChatColor color) {
      String teamName = "JT_" + color.name();
      if (teamName.length() > 16) {
         teamName = teamName.substring(0, 16);
      }

      WrapperPlayServerTeams packet = new WrapperPlayServerTeams(
         teamName, TeamMode.ADD_ENTITIES, (ScoreBoardTeamInfo)null, Collections.singletonList(target.getName())
      );
      PacketEvents.getAPI().getPlayerManager().sendPacket(receiver, packet);
   }

   private NamedTextColor getNamedTextColor(ChatColor color) {
      return switch (color) {
         case RED -> NamedTextColor.RED;
         case DARK_RED -> NamedTextColor.DARK_RED;
         case BLUE -> NamedTextColor.BLUE;
         case GREEN -> NamedTextColor.GREEN;
         case AQUA -> NamedTextColor.AQUA;
         case GOLD -> NamedTextColor.GOLD;
         case GRAY -> NamedTextColor.GRAY;
         case WHITE -> NamedTextColor.WHITE;
         case BLACK -> NamedTextColor.BLACK;
         case YELLOW -> NamedTextColor.YELLOW;
         case LIGHT_PURPLE -> NamedTextColor.LIGHT_PURPLE;
         case DARK_PURPLE -> NamedTextColor.DARK_PURPLE;
         case DARK_BLUE -> NamedTextColor.DARK_BLUE;
         case DARK_GREEN -> NamedTextColor.DARK_GREEN;
         case DARK_AQUA -> NamedTextColor.DARK_AQUA;
         case DARK_GRAY -> NamedTextColor.DARK_GRAY;
         default -> NamedTextColor.WHITE;
      };
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      if (this.enabled && this.usePacketEvents) {
         Player player = event.getPlayer();
         this.entityIdToUuid.put(player.getEntityId(), player.getUniqueId());
         this.createColorTeams(player);
         this.plugin.getTaskRunner().runAsyncTaskLater(() -> this.refreshGlow(player), 20L);
      }
   }

   private void createColorTeams(Player receiver) {
      if (!this.colorTeamsCreated.contains(receiver.getUniqueId())) {
         for (ChatColor color : ChatColor.values()) {
            if (color.isColor()) {
               String teamName = "JT_" + color.name();
               if (teamName.length() > 16) {
                  teamName = teamName.substring(0, 16);
               }

               ScoreBoardTeamInfo info = new ScoreBoardTeamInfo(
                  Component.text(teamName),
                  Component.text(color.toString()),
                  Component.empty(),
                  NameTagVisibility.ALWAYS,
                  CollisionRule.ALWAYS,
                  this.getNamedTextColor(color),
                  OptionData.NONE
               );
               WrapperPlayServerTeams packet = new WrapperPlayServerTeams(teamName, TeamMode.CREATE, info, new ArrayList());
               PacketEvents.getAPI().getPlayerManager().sendPacket(receiver, packet);
            }
         }

         this.colorTeamsCreated.add(receiver.getUniqueId());
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      Player player = event.getPlayer();
      UUID playerId = player.getUniqueId();
      this.entityIdToUuid.remove(player.getEntityId());
      this.glowingCache.remove(playerId);
      this.colorTeamsCreated.remove(playerId);
   }

   UUID getPlayerUuidByEntityId(int entityId) {
      return this.entityIdToUuid.get(entityId);
   }

   @EventHandler
   public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
      if (this.enabled && this.usePacketEvents) {
         Player player = event.getPlayer();
         this.plugin.getTaskRunner().runAsyncTaskLater(() -> {
            if (player.isOnline()) {
               this.createColorTeams(player);
               this.refreshGlow(player);
               Team team = this.plugin.getTeamManager().getPlayerTeamCached(player.getUniqueId());
               if (team != null) {
                  for (TeamPlayer member : team.getMembers()) {
                     Player other = Bukkit.getPlayer(member.getPlayerUuid());
                     if (other != null && other.isOnline() && !other.getUniqueId().equals(player.getUniqueId())) {
                        this.refreshGlow(other);
                     }
                  }
               }
            }
         }, 20L);
      }
   }

   @EventHandler
   public void onPlayerRespawn(PlayerRespawnEvent event) {
      if (this.enabled && this.usePacketEvents) {
         Player player = event.getPlayer();
         this.plugin.getTaskRunner().runAsyncTaskLater(() -> {
            if (player.isOnline()) {
               this.createColorTeams(player);
               this.refreshGlow(player);
               Team team = this.plugin.getTeamManager().getPlayerTeamCached(player.getUniqueId());
               if (team != null) {
                  for (TeamPlayer member : team.getMembers()) {
                     Player other = Bukkit.getPlayer(member.getPlayerUuid());
                     if (other != null && other.isOnline() && !other.getUniqueId().equals(player.getUniqueId())) {
                        this.refreshGlow(other);
                     }
                  }
               }
            }
         }, 10L);
      }
   }

   public void updateGlowForTeam(Team team) {
      if (this.enabled && team != null && this.usePacketEvents) {
         for (TeamPlayer member : team.getMembers()) {
            Player p = Bukkit.getPlayer(member.getPlayerUuid());
            if (p != null && p.isOnline()) {
               this.refreshGlow(p);
            }
         }
      }
   }

   public void stopGlowForPlayer(Player player, Team team) {
      if (this.enabled && this.usePacketEvents) {
         for (Player p : Bukkit.getOnlinePlayers()) {
            this.unsetGlow(player, p);
         }
      }
   }

   private ChatColor getRoleColor(TeamRole role) {
      String configKey = "settings.glow.colors." + role.name().toLowerCase();
      String colorName = this.plugin.getConfig().getString(configKey);
      if (colorName == null) {
         if (role == TeamRole.CO_OWNER) {
            return ChatColor.RED;
         } else {
            return role == TeamRole.OWNER ? ChatColor.DARK_RED : ChatColor.WHITE;
         }
      } else {
         try {
            return ChatColor.valueOf(colorName.toUpperCase());
         } catch (IllegalArgumentException e) {
            return ChatColor.WHITE;
         }
      }
   }

   boolean isActive() {
      return this.enabled && this.usePacketEvents;
   }

   Map<UUID, Map<UUID, ChatColor>> getGlowingCache() {
      return this.glowingCache;
   }

   JustTeams getPlugin() {
      return this.plugin;
   }

   private void startRangeCheckTask() {
      int interval = this.plugin.getConfig().getInt("settings.glow.check_interval", 20);
      if (this.usePacketEvents) {
         this.plugin.getTaskRunner().runAsyncTaskTimer(() -> {
            if (this.enabled && this.usePacketEvents) {
               Map<UUID, Team> snapshot = new HashMap<>();

               for (Player player : Bukkit.getOnlinePlayers()) {
                  Team team = this.plugin.getTeamManager().getPlayerTeamCached(player.getUniqueId());
                  if (team != null) {
                     snapshot.put(player.getUniqueId(), team);
                  }
               }

               this.refreshGlowBatch(snapshot);
            }
         }, interval, interval);
      }
   }

   private void refreshGlowBatch(Map<UUID, Team> snapshot) {
      Collection<? extends Player> online = Bukkit.getOnlinePlayers();

      for (Player target : online) {
         Team targetTeam = snapshot.get(target.getUniqueId());

         for (Player receiver : online) {
            if (!receiver.getUniqueId().equals(target.getUniqueId())) {
               this.refreshGlowForReceiver(target, receiver, targetTeam);
            }
         }
      }
   }

   void refreshGlowForReceiver(Player target, Player receiver, Team team) {
      if (team != null && team.isGlowEnabled()) {
         int range = this.plugin.getConfig().getInt("settings.glow.range", 30);
         if (target.getWorld().getUID().equals(receiver.getWorld().getUID()) && !(target.getLocation().distanceSquared(receiver.getLocation()) > range * range)
            )
          {
            if (this.plugin.getConfigManager().isDebugEnabled()) {
            }

            if (this.onlyShowOwnTeam) {
               Team receiverTeam = this.plugin.getTeamManager().getPlayerTeam(receiver.getUniqueId());
               if (receiverTeam != null && receiverTeam.getId() == team.getId()) {
                  ChatColor color = team.getColor() != null ? team.getColor() : this.getRoleColor(team.getMember(target.getUniqueId()).getRole());
                  this.setGlow(target, receiver, color);
               } else {
                  this.unsetGlow(target, receiver);
               }
            } else {
               ChatColor color = team.getColor() != null ? team.getColor() : this.getRoleColor(team.getMember(target.getUniqueId()).getRole());
               this.setGlow(target, receiver, color);
            }
         } else {
            this.unsetGlow(target, receiver);
         }
      } else {
         this.unsetGlow(target, receiver);
      }
   }

   public void refreshGlow(Player player) {
      if (this.enabled && this.usePacketEvents) {
         if (player.isOnline()) {
            this.plugin.getTeamManager().getPlayerTeamAsync(player.getUniqueId()).thenAccept(team -> {
               if (player.isOnline()) {
                  if (team != null && team.isGlowEnabled()) {
                     for (Player receiver : Bukkit.getOnlinePlayers()) {
                        if (!receiver.getUniqueId().equals(player.getUniqueId())) {
                           this.refreshGlowForReceiver(player, receiver, team);
                        }
                     }
                  } else {
                     for (Player receiver : Bukkit.getOnlinePlayers()) {
                        if (!receiver.getUniqueId().equals(player.getUniqueId())) {
                           this.unsetGlow(player, receiver);
                        }
                     }
                  }
               }
            }).exceptionally(ex -> {
               if (this.plugin.getConfigManager().isDebugEnabled()) {
                  this.plugin.getLogger().warning("Error refreshing glow for " + player.getName() + ": " + ex.getMessage());
                  ex.printStackTrace();
               }

               return null;
            });
         }
      }
   }
}
