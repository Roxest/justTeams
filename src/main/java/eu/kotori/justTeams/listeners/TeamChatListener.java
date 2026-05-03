package eu.kotori.justTeams.listeners;
import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.config.MessageManager;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamManager;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
public class TeamChatListener implements Listener {
    private final TeamManager teamManager;
    private final MessageManager messageManager;
    private final Set<UUID> teamChatEnabled = new HashSet<>();
    private final Set<UUID> chatSpyEnabled = new HashSet<>();
    private final MiniMessage miniMessage;
    public TeamChatListener(JustTeams plugin) {
        this.teamManager = plugin.getTeamManager();
        this.messageManager = plugin.getMessageManager();
        this.miniMessage = plugin.getMiniMessage();
    }
    public void toggleTeamChat(Player player) {
        if (!JustTeams.getInstance().getConfigManager().getBoolean("features.team_chat", true)) {
            messageManager.sendMessage(player, "feature_disabled");
            return;
        }
        
        UUID uuid = player.getUniqueId();
        if (teamChatEnabled.contains(uuid)) {
            teamChatEnabled.remove(uuid);
            messageManager.sendMessage(player, "team_chat_disabled");
        } else {
            if (teamManager.getPlayerTeam(uuid) == null) {
                messageManager.sendMessage(player, "player_not_in_team");
                return;
            }
            teamChatEnabled.add(uuid);
            messageManager.sendMessage(player, "team_chat_enabled");
        }
    }

    public void toggleChatSpy(Player player) {
        if (!player.hasPermission("justteams.chatspy")) {
            messageManager.sendRawMessage(player, "<red>You don't have permission to use chat spy!");
            return;
        }
        
        UUID uuid = player.getUniqueId();
        if (chatSpyEnabled.contains(uuid)) {
            chatSpyEnabled.remove(uuid);
            messageManager.sendRawMessage(player, "<red>Team chat spy disabled");
        } else {
            chatSpyEnabled.add(uuid);
            messageManager.sendRawMessage(player, "<green>Team chat spy enabled - You can now see all team chats");
        }
    }

    public boolean isChatSpyEnabled(UUID uuid) {
        return chatSpyEnabled.contains(uuid);
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!JustTeams.getInstance().getConfigManager().getBoolean("features.team_chat", true)) {
            return;
        }
        
        Player player = event.getPlayer();

        if (JustTeams.getInstance().getChatInputManager().hasPendingInput(player)) {
            return;
        }
        
        String messageContent = event.getMessage();
        
        Team team = teamManager.getPlayerTeamCached(player.getUniqueId());
        if (team == null) {
            return; 
        }
        
        boolean isCharacterBasedTeamChat = false;
        boolean isToggleTeamChat = teamChatEnabled.contains(player.getUniqueId());
        
        if (JustTeams.getInstance().getConfigManager().getBoolean("team_chat.character_enabled", true)) {
            String character = JustTeams.getInstance().getConfigManager().getString("team_chat.character", "#");
            
            if (character != null && !character.isEmpty() && !character.isBlank()) {
                boolean requireSpace = JustTeams.getInstance().getConfigManager().getBoolean("team_chat.require_space", false);
                
                if (requireSpace) {
                    isCharacterBasedTeamChat = messageContent.startsWith(character + " ");
                } else {
                    isCharacterBasedTeamChat = messageContent.startsWith(character);
                }
            }
        }
        
        if (!isToggleTeamChat && !isCharacterBasedTeamChat) {
            return; 
        }
        
        event.setCancelled(true);
        
        event.getRecipients().clear();
        
        final String finalMessageContent;
        if (isCharacterBasedTeamChat) {
            String character = JustTeams.getInstance().getConfigManager().getString("team_chat.character", "#");
            boolean requireSpace = JustTeams.getInstance().getConfigManager().getBoolean("team_chat.require_space", false);
            if (requireSpace) {
                finalMessageContent = messageContent.substring(character.length() + 1);
            } else {
                finalMessageContent = messageContent.substring(character.length());
            }
        } else {
            finalMessageContent = messageContent;
        }
        if (finalMessageContent.toLowerCase().contains("password") || finalMessageContent.toLowerCase().contains("pass")) {
            messageManager.sendMessage(player, "team_chat_password_warning");
            return;
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

    Component formattedMessage = miniMessage.deserialize(format,
        Placeholder.unparsed("player", player.getName()),
        Placeholder.component("prefix", prefixComponent),
        Placeholder.component("player_prefix", prefixComponent),
        Placeholder.component("suffix", suffixComponent),
        Placeholder.component("player_suffix", suffixComponent),
        Placeholder.unparsed("team_name", team.getName()),
        Placeholder.unparsed("message", finalMessageContent)
    );
        // Convert the component to a legacy string
        String legacyFormattedMessage = LegacyComponentSerializer.legacySection().serialize(formattedMessage);

        team.getMembers().stream()
                .map(member -> member.getBukkitPlayer())
                .filter(onlinePlayer -> onlinePlayer != null)
                // Send the string, not the component!
                .forEach(onlinePlayer -> onlinePlayer.sendMessage(legacyFormattedMessage));
                
        Bukkit.getOnlinePlayers().stream()
                .filter(spy -> chatSpyEnabled.contains(spy.getUniqueId()))
                .filter(spy -> !team.isMember(spy.getUniqueId()))
                .forEach(spy -> {
                    Component spyMessage = miniMessage.deserialize(
                        "<dark_gray>[<red>SPY<dark_gray>] <gray>[<yellow>" + team.getName() + "<gray>] <white>" + 
                        player.getName() + "<dark_gray>: <white>" + finalMessageContent
                    );
                    spy.sendMessage(spyMessage);
                });
        
        if (JustTeams.getInstance().getConfigManager().isCrossServerSyncEnabled()) {
            JustTeams.getInstance().getTaskRunner().runAsync(() -> {
                try {
                    String currentServer = JustTeams.getInstance().getConfigManager().getServerIdentifier();
                    
                    if (JustTeams.getInstance().getConfigManager().isRedisEnabled() &&
                        JustTeams.getInstance().getRedisManager().isAvailable()) {
                        
                        JustTeams.getInstance().getRedisManager().publishTeamChat(
                            team.getId(),
                            player.getUniqueId().toString(),
                            player.getName(),
                            finalMessageContent
                        ).thenAccept(success -> {
                            if (success) {
                                JustTeams.getInstance().getLogger().info(
                                    "✓ Team chat sent via Redis (instant)");
                            } else {
                                JustTeams.getInstance().getLogger().warning(
                                    "Redis publish failed, storing in MySQL for polling");
                                storeChatToMySQL(team.getId(), player.getUniqueId().toString(), 
                                               finalMessageContent, currentServer);
                            }
                        }).exceptionally(ex -> {
                            JustTeams.getInstance().getLogger().warning(
                                "Redis error: " + ex.getMessage() + ", using MySQL fallback");
                            storeChatToMySQL(team.getId(), player.getUniqueId().toString(), 
                                           finalMessageContent, currentServer);
                            return null;
                        });
                    } else {
                        storeChatToMySQL(team.getId(), player.getUniqueId().toString(), 
                                       finalMessageContent, currentServer);
                    }
                } catch (Exception e) {
                    JustTeams.getInstance().getLogger().warning("Failed to send cross-server message: " + e.getMessage());
                }
            });
        }
    }
    
    private void storeChatToMySQL(int teamId, String playerUuid, String message, String sourceServer) {
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
}
