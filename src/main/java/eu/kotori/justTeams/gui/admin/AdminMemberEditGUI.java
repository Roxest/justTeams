package eu.kotori.justTeams.gui.admin;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamPlayer;
import eu.kotori.justTeams.team.TeamRole;
import eu.kotori.justTeams.util.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class AdminMemberEditGUI implements InventoryHolder {
    private final JustTeams plugin;
    private final Player viewer;
    private final Team team;
    private final TeamPlayer member;
    private final Inventory inventory;
    
    public AdminMemberEditGUI(JustTeams plugin, Player viewer, Team team, TeamPlayer member) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.team = team;
        this.member = member;
        
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(member.getPlayerUuid());
        String playerName = targetPlayer.getName() != null ? targetPlayer.getName() : "Unknown";
        
        this.inventory = Bukkit.createInventory(this, 54, 
            PlainTextComponentSerializer.plainText().serialize(plugin.getMiniMessage().deserialize("<gold><bold>ᴇᴅɪᴛ ᴍᴇᴍʙᴇʀ</bold></gold> <dark_gray>» <white>" + playerName)));
        initializeItems();
    }
    
    private void initializeItems() {
        inventory.clear();
        
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(member.getPlayerUuid());
        String playerName = targetPlayer.getName() != null ? targetPlayer.getName() : "Unknown";
        boolean isOwner = member.getPlayerUuid().equals(team.getOwnerUuid());
        
        List<String> infoLore = new ArrayList<>();
        infoLore.add("<gray>Name: <white>" + playerName);
        infoLore.add("<gray>Role: <white>" + member.getRole().toString());
        infoLore.add("<gray>Online: " + (member.isOnline() ? "<green>✓" : "<red>✗"));
        infoLore.add("");
        infoLore.add("<gray>Team: <white>" + team.getName());
        
        inventory.setItem(4, new ItemBuilder(Material.PLAYER_HEAD)
                .withName((isOwner ? "<gold><bold>★ " : "<white>") + playerName)
                .withLore(infoLore)
                .asPlayerHead(member.getPlayerUuid())
                .build());
        
        inventory.setItem(19, new ItemBuilder(member.canWithdraw() ? Material.LIME_DYE : Material.GRAY_DYE)
                .withName(member.canWithdraw() ? "<green><bold>ᴡɪᴛʜᴅʀᴀᴡ</bold> <gray>(Enabled)" : "<gray><bold>ᴡɪᴛʜᴅʀᴀᴡ</bold> <gray>(Disabled)")
                .withLore(
                    "<gray>Status: " + (member.canWithdraw() ? "<green>Enabled" : "<red>Disabled"),
                    "",
                    "<gray>Allow member to withdraw from bank",
                    "",
                    "<yellow>Click to toggle"
                )
                .withAction("toggle-withdraw")
                .build());
        
        inventory.setItem(20, new ItemBuilder(member.canUseEnderChest() ? Material.LIME_DYE : Material.GRAY_DYE)
                .withName(member.canUseEnderChest() ? "<dark_purple><bold>ᴇɴᴅᴇʀᴄʜᴇsᴛ</bold> <gray>(Enabled)" : "<gray><bold>ᴇɴᴅᴇʀᴄʜᴇsᴛ</bold> <gray>(Disabled)")
                .withLore(
                    "<gray>Status: " + (member.canUseEnderChest() ? "<green>Enabled" : "<red>Disabled"),
                    "",
                    "<gray>Allow member to use team enderchest",
                    "",
                    "<yellow>Click to toggle"
                )
                .withAction("toggle-enderchest")
                .build());
        
        inventory.setItem(21, new ItemBuilder(member.canSetHome() ? Material.LIME_DYE : Material.GRAY_DYE)
                .withName(member.canSetHome() ? "<aqua><bold>sᴇᴛ ʜᴏᴍᴇ</bold> <gray>(Enabled)" : "<gray><bold>sᴇᴛ ʜᴏᴍᴇ</bold> <gray>(Disabled)")
                .withLore(
                    "<gray>Status: " + (member.canSetHome() ? "<green>Enabled" : "<red>Disabled"),
                    "",
                    "<gray>Allow member to set team home",
                    "",
                    "<yellow>Click to toggle"
                )
                .withAction("toggle-sethome")
                .build());
        
        inventory.setItem(22, new ItemBuilder(member.canUseHome() ? Material.LIME_DYE : Material.GRAY_DYE)
                .withName(member.canUseHome() ? "<light_purple><bold>ᴜsᴇ ʜᴏᴍᴇ</bold> <gray>(Enabled)" : "<gray><bold>ᴜsᴇ ʜᴏᴍᴇ</bold> <gray>(Disabled)")
                .withLore(
                    "<gray>Status: " + (member.canUseHome() ? "<green>Enabled" : "<red>Disabled"),
                    "",
                    "<gray>Allow member to teleport to team home",
                    "",
                    "<yellow>Click to toggle"
                )
                .withAction("toggle-usehome")
                .build());
        
        if (!isOwner) {
            inventory.setItem(29, new ItemBuilder(Material.EMERALD)
                    .withName("<green><bold>ᴘʀᴏᴍᴏᴛᴇ</bold>")
                    .withLore(
                        "<gray>Current Role: <white>" + member.getRole().toString(),
                        "",
                        "<gray>Promote to " + (member.getRole() == TeamRole.MEMBER ? "Co-Owner" : "N/A"),
                        "",
                        "<yellow>Click to promote"
                    )
                    .withAction("promote-member")
                    .build());
            
            if (member.getRole() == TeamRole.CO_OWNER) {
                inventory.setItem(30, new ItemBuilder(Material.REDSTONE)
                        .withName("<red><bold>ᴅᴇᴍᴏᴛᴇ</bold>")
                        .withLore(
                            "<gray>Current Role: <white>" + member.getRole().toString(),
                            "",
                            "<gray>Demote to Member",
                            "",
                            "<yellow>Click to demote"
                        )
                        .withAction("demote-member")
                        .build());
            }
        }
        
        if (!isOwner) {
            inventory.setItem(33, new ItemBuilder(Material.TNT)
                    .withName("<dark_red><bold>ᴋɪᴄᴋ ᴍᴇᴍʙᴇʀ</bold>")
                    .withLore(
                        "<gray>Remove <white>" + playerName + " <gray>from the team",
                        "<red>⚠ This action cannot be undone!",
                        "",
                        "<yellow>Click to kick member"
                    )
                    .withAction("kick-member")
                    .build());
        }
        
        inventory.setItem(45, new ItemBuilder(Material.ARROW)
                .withName("<gray><bold>◀ ʙᴀᴄᴋ</bold>")
                .withLore("<gray>Return to team management")
                .withAction("back-button")
                .build());
        
        ItemStack fillItem = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .withName(" ")
                .build();
        for (int i = 0; i < 54; i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, fillItem);
            }
        }
    }
    
    public void open() {
        viewer.openInventory(inventory);
    }
    
    public Team getTeam() {
        return team;
    }
    
    public TeamPlayer getMember() {
        return member;
    }
    
    @NotNull
    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
