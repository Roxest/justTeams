package eu.kotori.justTeams.redis;

import eu.kotori.justTeams.JustTeams;
import redis.clients.jedis.JedisPubSub;
import eu.kotori.justTeams.team.Team;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class TeamMessageSubscriber extends JedisPubSub {
   private final JustTeams plugin;
   private final MiniMessage mm = MiniMessage.miniMessage();

   public TeamMessageSubscriber(JustTeams plugin) {
      this.plugin = plugin;
   }

   public void onMessage(String channel, String message) {
      try {
         String[] parts = message.split("\\|", 5);
         if (parts.length < 4) {
            this.plugin.getLogger().warning("Invalid Redis message format: " + message);
            return;
         }

         int teamId = Integer.parseInt(parts[0]);
         UUID senderUuid = UUID.fromString(parts[1]);
         String senderName = parts[2];
         String messageText = parts[3];
         long timestamp = parts.length > 4 ? Long.parseLong(parts[4]) : System.currentTimeMillis();
         long latency = System.currentTimeMillis() - timestamp;
         Team team = this.plugin.getTeamManager().getTeamById(teamId).orElse(null);
         if (team == null) {
            this.plugin.getLogger().warning("Received message for unknown team ID: " + teamId);
            return;
         }

         String currentServer = this.plugin.getConfigManager().getServerIdentifier();
         Player sender = Bukkit.getPlayer(senderUuid);
         if (sender != null && sender.isOnline()) {
            this.plugin.getLogger().fine("Skipping Redis message from local player: " + senderName);
            return;
         }

         String format = this.plugin.getMessageManager().getRawMessage("team_chat_format");
         String playerPrefix = "";
         String playerSuffix = "";
         Player onlineSender = Bukkit.getPlayer(senderUuid);
         if (onlineSender != null && onlineSender.isOnline()) {
            playerPrefix = this.plugin.getPlayerPrefix(onlineSender);
            playerSuffix = this.plugin.getPlayerSuffix(onlineSender);
         }

         String teamColorTag = "";
         if (team.getColor() != null) {
            ChatColor color = team.getColor();
            switch (color) {
               case BLACK:
                  teamColorTag = "<black>";
                  break;
               case DARK_BLUE:
                  teamColorTag = "<dark_blue>";
                  break;
               case DARK_GREEN:
                  teamColorTag = "<dark_green>";
                  break;
               case DARK_AQUA:
                  teamColorTag = "<dark_aqua>";
                  break;
               case DARK_RED:
                  teamColorTag = "<dark_red>";
                  break;
               case DARK_PURPLE:
                  teamColorTag = "<dark_purple>";
                  break;
               case GOLD:
                  teamColorTag = "<gold>";
                  break;
               case GRAY:
                  teamColorTag = "<gray>";
                  break;
               case DARK_GRAY:
                  teamColorTag = "<dark_gray>";
                  break;
               case BLUE:
                  teamColorTag = "<blue>";
                  break;
               case GREEN:
                  teamColorTag = "<green>";
                  break;
               case AQUA:
                  teamColorTag = "<aqua>";
                  break;
               case RED:
                  teamColorTag = "<red>";
                  break;
               case LIGHT_PURPLE:
                  teamColorTag = "<light_purple>";
                  break;
               case YELLOW:
                  teamColorTag = "<yellow>";
                  break;
               case WHITE:
                  teamColorTag = "<white>";
                  break;
               default:
                  teamColorTag = "";
            }
         }

         String preFormat = format.replace("<team_color>", teamColorTag);
         Component component = this.mm
            .deserialize(
               preFormat,
               new TagResolver[]{
                  Placeholder.unparsed("team", team.getName()),
                  Placeholder.unparsed("team_name", team.getName()),
                  Placeholder.unparsed("team_tag", team.getTag()),
                  Placeholder.unparsed("player", senderName),
                  Placeholder.unparsed("prefix", playerPrefix == null ? "" : playerPrefix),
                  Placeholder.unparsed("player_prefix", playerPrefix == null ? "" : playerPrefix),
                  Placeholder.unparsed("suffix", playerSuffix == null ? "" : playerSuffix),
                  Placeholder.unparsed("player_suffix", playerSuffix == null ? "" : playerSuffix),
                  Placeholder.unparsed("message", messageText)
               }
            );
         this.plugin.getTaskRunner().run(() -> {
            int delivered = 0;

            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
               if (team.isMember(onlinePlayer.getUniqueId())) {
                  onlinePlayer.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(component));
                  delivered++;
               }
            }

            this.plugin.getLogger().info(String.format("✓ Redis message delivered to %d players (latency: %dms) [%s]", delivered, latency, channel));
         });
      } catch (Exception e) {
         this.plugin.getLogger().warning("Error processing Redis message: " + e.getMessage());
         e.printStackTrace();
      }
   }

   public void onSubscribe(String channel, int subscribedChannels) {
      this.plugin.getLogger().info("✓ Subscribed to Redis channel: " + channel + " (total: " + subscribedChannels + ")");
   }

   public void onUnsubscribe(String channel, int subscribedChannels) {
      this.plugin.getLogger().info("Unsubscribed from Redis channel: " + channel + " (remaining: " + subscribedChannels + ")");
   }
}
