package eu.kotori.justTeams.commands;
import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.config.MessageManager;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
public class TeamMessageCommand implements CommandExecutor, TabCompleter {
    private final TeamManager teamManager;
    private final MessageManager messageManager;
    private final MiniMessage miniMessage;
    private final ConcurrentHashMap<UUID, Long> messageCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> messageCounts = new ConcurrentHashMap<>();
    private static final long MESSAGE_COOLDOWN = 2000;
    private static final int MAX_MESSAGES_PER_MINUTE = 20;
    private static final int MAX_MESSAGE_LENGTH = 200;
    public TeamMessageCommand(JustTeams plugin) {
        this.teamManager = plugin.getTeamManager();
        this.messageManager = plugin.getMessageManager();
        this.miniMessage = plugin.getMiniMessage();
        plugin.getTaskRunner().runTimer(() -> {
            messageCounts.clear();
        }, 20L * 60, 20L * 60);
    }
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messageManager.sendMessage(sender, "player_only");
            return true;
        }
        if (args.length == 0) {
            messageManager.sendRawMessage(player, "<gray>Usage: /" + label + " <message>");
            return true;
        }
        if (!checkMessageSpam(player)) {
            messageManager.sendMessage(player, "message_spam_protection");
            return true;
        }
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return true;
        }
        String message = String.join(" ", args);
        if (message.length() > MAX_MESSAGE_LENGTH) {
            messageManager.sendMessage(player, "message_too_long");
            return true;
        }
        if (containsInappropriateContent(message)) {
            messageManager.sendMessage(player, "inappropriate_message");
            return true;
        }
        String format = messageManager.getRawMessage("team_chat_format");
        
        String playerPrefix = JustTeams.getInstance().getPlayerPrefix(player);
        String playerSuffix = JustTeams.getInstance().getPlayerSuffix(player);
        
    Component prefixComponent = (playerPrefix != null && !playerPrefix.isEmpty())
        ? LegacyComponentSerializer.legacyAmpersand().deserialize(playerPrefix)
        : Component.empty();
    Component suffixComponent = (playerSuffix != null && !playerSuffix.isEmpty())
        ? LegacyComponentSerializer.legacyAmpersand().deserialize(playerSuffix)
        : Component.empty();

    // 1. Build the Component as usual
    Component formattedMessageComponent = miniMessage.deserialize(format,
        Placeholder.unparsed("player", player.getName()),
        Placeholder.component("prefix", prefixComponent),
        Placeholder.component("player_prefix", prefixComponent),
        Placeholder.component("suffix", suffixComponent),
        Placeholder.component("player_suffix", suffixComponent),
        Placeholder.unparsed("team_name", team.getName()),
        Placeholder.unparsed("message", message)
    );
    
    // 2. Convert the Component into a standard Bukkit String!
    String legacyStringMessage = LegacyComponentSerializer.legacySection().serialize(formattedMessageComponent);
        
    // 3. Send the String, NOT the Component
    team.getMembers().stream()
            .map(member -> member.getBukkitPlayer())
            .filter(onlinePlayer -> onlinePlayer != null)
            .forEach(onlinePlayer -> onlinePlayer.sendMessage(legacyStringMessage));
        
        if (JustTeams.getInstance().getConfigManager().isCrossServerSyncEnabled()) {
            JustTeams.getInstance().getTaskRunner().runAsync(() -> {
                try {
                    String currentServer = JustTeams.getInstance().getConfigManager().getServerIdentifier();
                    
                    if (JustTeams.getInstance().getConfigManager().isRedisEnabled() &&
                        JustTeams.getInstance().getRedisManager().isAvailable()) {
                        
                        JustTeams.getInstance().getRedisManager().publishTeamMessage(
                            team.getId(),
                            player.getUniqueId().toString(),
                            player.getName(),
                            message
                        ).thenAccept(success -> {
                            if (success) {
                                JustTeams.getInstance().getLogger().info(
                                    "✓ Team message sent via Redis (instant)");
                            } else {
                                JustTeams.getInstance().getLogger().warning(
                                    "Redis publish failed, storing in MySQL for polling");
                                storeMessageToMySQL(team.getId(), player.getUniqueId().toString(), 
                                                  message, currentServer);
                            }
                        }).exceptionally(ex -> {
                            JustTeams.getInstance().getLogger().warning(
                                "Redis error: " + ex.getMessage() + ", using MySQL fallback");
                            storeMessageToMySQL(team.getId(), player.getUniqueId().toString(), 
                                              message, currentServer);
                            return null;
                        });
                    } else {
                        storeMessageToMySQL(team.getId(), player.getUniqueId().toString(), 
                                          message, currentServer);
                    }
                } catch (Exception e) {
                    JustTeams.getInstance().getLogger().warning("Failed to send cross-server message: " + e.getMessage());
                }
            });
        }
        
        return true;
    }
    
    private void storeMessageToMySQL(int teamId, String playerUuid, String message, String sourceServer) {
        try {
            JustTeams.getInstance().getStorageManager().getStorage().addCrossServerMessage(
                teamId,
                playerUuid,
                message,
                sourceServer 
            );
        } catch (Exception e) {
            JustTeams.getInstance().getLogger().warning(
                "Failed to store message to MySQL: " + e.getMessage());
        }
    }
    
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return new ArrayList<>();
    }
    private boolean checkMessageSpam(Player player) {
        long currentTime = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        Long lastMessage = messageCooldowns.get(playerId);
        if (lastMessage != null && currentTime - lastMessage < MESSAGE_COOLDOWN) {
            return false;
        }
        int count = messageCounts.getOrDefault(playerId, 0);
        if (count >= MAX_MESSAGES_PER_MINUTE) {
            return false;
        }
        messageCooldowns.put(playerId, currentTime);
        messageCounts.put(playerId, count + 1);
        return true;
    }
    private boolean containsInappropriateContent(String message) {
        String lowerMessage = message.toLowerCase();
        String[] inappropriate = {
            "admin", "mod", "staff", "owner", "server", "minecraft", "bukkit", "spigot",
            "hack", "cheat", "exploit", "bug", "glitch", "dupe", "duplicate"
        };
        for (String word : inappropriate) {
            if (lowerMessage.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
