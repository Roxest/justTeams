package eu.kotori.justTeams.gui.admin;
import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamPlayer;
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

public class AdminTeamManageGUI implements InventoryHolder {
    private final JustTeams plugin;
    private final Player viewer;
    private final Team targetTeam;
    private final Inventory inventory;
    private final int memberPage;
    private static final int MEMBERS_PER_PAGE = 21;
    
    public AdminTeamManageGUI(JustTeams plugin, Player viewer, Team targetTeam) {
        this(plugin, viewer, targetTeam, 0);
    }
    
    public AdminTeamManageGUI(JustTeams plugin, Player viewer, Team targetTeam, int memberPage) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.targetTeam = targetTeam;
        this.memberPage = memberPage;
        this.inventory = Bukkit.createInventory(this, 54, 
            PlainTextComponentSerializer.plainText().serialize(plugin.getMiniMessage().deserialize("<gold><bold>ᴍᴀɴᴀɢᴇ</bold></gold> <dark_gray>» <white>" + targetTeam.getName())));
        initializeItems();
    }
    
    private void initializeItems() {
        inventory.clear();
        
        OfflinePlayer owner = Bukkit.getOfflinePlayer(targetTeam.getOwnerUuid());
        String ownerName = owner.getName() != null ? owner.getName() : "Unknown";
        
        inventory.setItem(0, new ItemBuilder(Material.NAME_TAG)
                .withName("<yellow><bold>ᴛᴇᴀᴍ ɪɴғᴏ")
                .withLore(
                    "<gray>Name: <white>" + targetTeam.getName(),
                    "<gray>Tag: <white>" + targetTeam.getTag(),
                    "<gray>Owner: <white>" + ownerName,
                    "<gray>Members: <white>" + targetTeam.getMembers().size() + "<gray>/<white>" + plugin.getConfigManager().getMaxTeamSize()
                )
                .build());
        
        inventory.setItem(1, new ItemBuilder(Material.WRITABLE_BOOK)
                .withName("<aqua><bold>ᴅᴇsᴄʀɪᴘᴛɪᴏɴ")
                .withLore(
                    "<gray>Current: <white>" + targetTeam.getDescription(),
                    "",
                    "<yellow>Click to change description"
                )
                .withAction("edit-description")
                .build());
        
        inventory.setItem(2, new ItemBuilder(Material.PAPER)
                .withName("<gold><bold>ʀᴇɴᴀᴍᴇ ᴛᴇᴀᴍ")
                .withLore(
                    "<gray>Current: <white>" + targetTeam.getName(),
                    "",
                    "<yellow>Click to rename team",
                    "<gray>(No cooldown for admins)"
                )
                .withAction("rename-team")
                .build());
        
        inventory.setItem(3, new ItemBuilder(Material.OAK_SIGN)
                .withName("<light_purple><bold>ᴄʜᴀɴɢᴇ ᴛᴀɢ")
                .withLore(
                    "<gray>Current: <white>" + targetTeam.getTag(),
                    "",
                    "<yellow>Click to change tag"
                )
                .withAction("edit-tag")
                .build());
        
        inventory.setItem(9, new ItemBuilder(targetTeam.isPublic() ? Material.LIME_DYE : Material.GRAY_DYE)
                .withName(targetTeam.isPublic() ? "<green><bold>ᴘᴜʙʟɪᴄ <gray>(Click to make private)" : "<gray><bold>ᴘʀɪᴠᴀᴛᴇ <gray>(Click to make public)")
                .withLore(
                    "<gray>Status: " + (targetTeam.isPublic() ? "<green>Public" : "<red>Private"),
                    "",
                    "<yellow>Click to toggle"
                )
                .withAction("toggle-public")
                .build());
        
        inventory.setItem(10, new ItemBuilder(targetTeam.isPvpEnabled() ? Material.NETHERITE_SWORD : Material.WOODEN_SWORD)
                .withName(targetTeam.isPvpEnabled() ? "<red><bold>ᴘᴠᴘ ᴇɴᴀʙʟᴇᴅ <gray>(Click to disable)" : "<gray><bold>ᴘᴠᴘ ᴅɪsᴀʙʟᴇᴅ <gray>(Click to enable)")
                .withLore(
                    "<gray>PvP: " + (targetTeam.isPvpEnabled() ? "<green>Enabled" : "<red>Disabled"),
                    "",
                    "<yellow>Click to toggle"
                )
                .withAction("toggle-pvp")
                .build());
        
        inventory.setItem(11, new ItemBuilder(Material.DIAMOND)
                .withName("<yellow><bold>ʙᴀʟᴀɴᴄᴇ")
                .withLore(
                    "<gray>Current: <white>" + String.format("%.2f", targetTeam.getBalance()),
                    "",
                    "<yellow>Click <gray>to set balance"
                )
                .withAction("edit-balance")
                .build());
        
        inventory.setItem(12, new ItemBuilder(Material.IRON_SWORD)
                .withName("<red><bold>sᴛᴀᴛɪsᴛɪᴄs")
                .withLore(
                    "<gray>Kills: <white>" + targetTeam.getKills(),
                    "<gray>Deaths: <white>" + targetTeam.getDeaths(),
                    "<gray>K/D: <white>" + (targetTeam.getDeaths() > 0 ? String.format("%.2f", (double) targetTeam.getKills() / targetTeam.getDeaths()) : "∞"),
                    "",
                    "<yellow>Click to edit stats"
                )
                .withAction("edit-stats")
                .build());
        
        inventory.setItem(13, new ItemBuilder(Material.ENDER_CHEST)
                .withName("<dark_purple><bold>ᴇɴᴅᴇʀᴄʜᴇsᴛ")
                .withLore(
                    "<gray>View team's ender chest",
                    "",
                    "<yellow>Click to open"
                )
                .withAction("view-enderchest")
                .build());
        
        inventory.setItem(14, new ItemBuilder(Material.TNT)
                .withName("<dark_red><bold>ᴅɪsʙᴀɴᴅ ᴛᴇᴀᴍ")
                .withLore(
                    "<gray>Permanently delete this team",
                    "<red>⚠ This cannot be undone!",
                    "",
                    "<yellow>Click to disband"
                )
                .withAction("disband-team")
                .build());
        
        ItemStack divider = new ItemBuilder(Material.YELLOW_STAINED_GLASS_PANE)
                .withName("<yellow><bold>▬▬▬ ᴍᴇᴍʙᴇʀs ▬▬▬")
                .build();
        for (int i = 18; i < 27; i++) {
            inventory.setItem(i, divider);
        }
        
        List<TeamPlayer> members = targetTeam.getMembers();
        int totalPages = (int) Math.ceil((double) members.size() / MEMBERS_PER_PAGE);
        int startIndex = memberPage * MEMBERS_PER_PAGE;
        int endIndex = Math.min(startIndex + MEMBERS_PER_PAGE, members.size());
        
        for (int i = startIndex; i < endIndex; i++) {
            TeamPlayer member = members.get(i);
            int slot = 27 + (i - startIndex);
            inventory.setItem(slot, createMemberItem(member));
        }
        
        if (memberPage > 0) {
            inventory.setItem(48, new ItemBuilder(Material.ARROW)
                    .withName("<yellow>◀ ᴘʀᴇᴠɪᴏᴜs")
                    .withAction("prev-members")
                    .build());
        }
        
        inventory.setItem(49, new ItemBuilder(Material.PLAYER_HEAD)
                .withName("<aqua><bold>ᴍᴇᴍʙᴇʀs")
                .withLore(
                    "<gray>Total: <white>" + members.size(),
                    "<gray>Page: <white>" + (memberPage + 1) + " <gray>of <white>" + Math.max(1, totalPages)
                )
                .build());
        
        if (memberPage < totalPages - 1) {
            inventory.setItem(50, new ItemBuilder(Material.ARROW)
                    .withName("<yellow>ɴᴇxᴛ ▶")
                    .withAction("next-members")
                    .build());
        }
        
        inventory.setItem(45, new ItemBuilder(Material.ARROW)
                .withName("<gray><bold>◀ ʙᴀᴄᴋ")
                .withLore("<gray>Return to team list")
                .withAction("back-button")
                .build());
        
        inventory.setItem(53, new ItemBuilder(Material.LIME_DYE)
                .withName("<green><bold>ʀᴇғʀᴇsʜ")
                .withLore("<gray>Refresh this menu")
                .withAction("refresh")
                .build());
        ItemStack fillItem = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .withName(" ")
                .build();
        for (int i = 45; i < 54; i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, fillItem);
            }
        }
    }
    
    private ItemStack createMemberItem(TeamPlayer member) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(member.getPlayerUuid());
        String playerName = player.getName() != null ? player.getName() : "Unknown";
        boolean isOwner = member.getPlayerUuid().equals(targetTeam.getOwnerUuid());
        
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Role: <white>" + member.getRole().toString());
        lore.add("<gray>Online: " + (member.isOnline() ? "<green>✓" : "<red>✗"));
        lore.add("");
        lore.add("<gray>Permissions:");
        lore.add("<gray>- Withdraw: " + (member.canWithdraw() ? "<green>✓" : "<red>✗"));
        lore.add("<gray>- Ender Chest: " + (member.canUseEnderChest() ? "<green>✓" : "<red>✗"));
        lore.add("<gray>- Set Home: " + (member.canSetHome() ? "<green>✓" : "<red>✗"));
        lore.add("<gray>- Use Home: " + (member.canUseHome() ? "<green>✓" : "<red>✗"));
        
        if (!isOwner) {
            lore.add("");
            lore.add("<yellow>Left-Click <gray>to edit permissions");
            lore.add("<yellow>Right-Click <gray>to kick member");
        }
        
        return new ItemBuilder(Material.PLAYER_HEAD)
                .withName((isOwner ? "<gold><bold>★ " : "<white>") + playerName)
                .withLore(lore)
                .asPlayerHead(member.getPlayerUuid())
                .withAction("member-" + member.getPlayerUuid().toString())
                .build();
    }
    
    public void open() {
        viewer.openInventory(inventory);
    }
    
    public Team getTargetTeam() {
        return targetTeam;
    }
    
    public int getMemberPage() {
        return memberPage;
    }
    
    public Inventory getInventory() {
        return inventory;
    }
}
