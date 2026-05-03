package eu.kotori.justTeams.gui;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.storage.IDataStorage;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.util.GuiConfigManager;
import eu.kotori.justTeams.util.ItemBuilder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

public class InvitesGUI implements IRefreshableGUI, InventoryHolder {
    private final JustTeams plugin;
    private final Player viewer;
    private final Inventory inventory;

    public InvitesGUI(JustTeams plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        
        GuiConfigManager guiManager = plugin.getGuiConfigManager();
        ConfigurationSection guiConfig = guiManager.getGUI("invites-gui");
        
        String title = guiConfig != null ? guiConfig.getString("title", "ᴘᴇɴᴅɪɴɢ ɪɴᴠɪᴛᴇs") : "ᴘᴇɴᴅɪɴɢ ɪɴᴠɪᴛᴇs";
        int size = guiConfig != null ? guiConfig.getInt("size", 54) : 54;
        
        this.inventory = Bukkit.createInventory(this, size, PlainTextComponentSerializer.plainText().serialize(plugin.getMiniMessage().deserialize(title)));
        initializeItems();
    }

    public void initializeItems() {
        inventory.clear();
        
        GuiConfigManager guiManager = plugin.getGuiConfigManager();
        ConfigurationSection guiConfig = guiManager.getGUI("invites-gui");
        
        if (guiConfig == null) {
            plugin.getLogger().warning("invites-gui section not found in gui.yml!");
            return;
        }
        
        ConfigurationSection itemsConfig = guiConfig.getConfigurationSection("items");
        if (itemsConfig == null) return;
        
        ItemStack border = new ItemBuilder(guiManager.getMaterial("invites-gui.fill-item.material", Material.GRAY_STAINED_GLASS_PANE))
                .withName(guiManager.getString("invites-gui.fill-item.name", " "))
                .build();
        
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, border);
        }
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, border);
        }
        
        plugin.getTaskRunner().runAsync(() -> {
            List<IDataStorage.TeamInvite> inviteDetails = plugin.getStorageManager().getStorage()
                .getPlayerInvitesWithDetails(viewer.getUniqueId());
            
            plugin.getTaskRunner().runOnEntity(viewer, () -> {
                if (inviteDetails.isEmpty()) {
                    ConfigurationSection noInvitesConfig = itemsConfig.getConfigurationSection("no-invites");
                    if (noInvitesConfig != null) {
                        ItemStack noInvitesItem = new ItemBuilder(Material.matchMaterial(noInvitesConfig.getString("material", "BARRIER")))
                                .withName(noInvitesConfig.getString("name", "<red><bold>No Pending Invites</bold></red>"))
                                .withLore(noInvitesConfig.getStringList("lore"))
                                .build();
                        inventory.setItem(noInvitesConfig.getInt("slot", 22), noInvitesItem);
                    }
                } else {
                    int slot = 9;
                    for (IDataStorage.TeamInvite invite : inviteDetails) {
                        if (slot >= 45) break;
                        
                        Team team = plugin.getTeamManager().getTeamByName(invite.teamName());
                        if (team == null) continue;
                        
                        ConfigurationSection teamIconConfig = itemsConfig.getConfigurationSection("team-icon");
                        if (teamIconConfig == null) continue;
                        
                        String name = teamIconConfig.getString("name", "<gradient:#4C9D9D:#4C96D2><bold><team_name></bold></gradient>")
                                .replace("<team_name>", invite.teamName())
                                .replace("<team_tag>", team.getTag());
                        
                        List<String> loreList = teamIconConfig.getStringList("lore");
                        loreList = loreList.stream()
                                .map(line -> line
                                        .replace("<team_name>", invite.teamName())
                                        .replace("<team_tag>", team.getTag())
                                        .replace("<inviter>", invite.inviterName())
                                        .replace("<member_count>", String.valueOf(team.getMembers().size()))
                                        .replace("<max_members>", String.valueOf(plugin.getConfigManager().getMaxTeamSize()))
                                        .replace("<description>", team.getDescription() != null ? team.getDescription() : "No description")
                                )
                                .collect(Collectors.toList());
                        
                        ItemStack teamIcon = new ItemBuilder(Material.matchMaterial(teamIconConfig.getString("material", "DIAMOND")))
                                .withName(name)
                                .withLore(loreList)
                                .withAction("team-icon")
                                .withData("team_id", String.valueOf(invite.teamId()))
                                .withData("team_name", invite.teamName())
                                .build();
                        
                        inventory.setItem(slot++, teamIcon);
                    }
                }
            });
        });
        
        ConfigurationSection backConfig = itemsConfig.getConfigurationSection("back-button");
        if (backConfig != null) {
            ItemStack backButton = new ItemBuilder(Material.matchMaterial(backConfig.getString("material", "ARROW")))
                    .withName(backConfig.getString("name", "<gray><bold>ʙᴀᴄᴋ</bold></gray>"))
                    .withLore(backConfig.getStringList("lore"))
                    .withAction("back-button")
                    .build();
            inventory.setItem(backConfig.getInt("slot", 49), backButton);
        }
        
        ConfigurationSection closeConfig = itemsConfig.getConfigurationSection("close-button");
        if (closeConfig != null) {
            ItemStack closeButton = new ItemBuilder(Material.matchMaterial(closeConfig.getString("material", "BARRIER")))
                    .withName(closeConfig.getString("name", "<red><bold>ᴄʟᴏsᴇ</bold></red>"))
                    .withLore(closeConfig.getStringList("lore"))
                    .withAction("close-button")
                    .build();
            inventory.setItem(closeConfig.getInt("slot", 53), closeButton);
        }
    }

    public void open() {
        viewer.openInventory(inventory);
    }

    @Override
    public void refresh() {
        initializeItems();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
    
    public Player getViewer() {
        return viewer;
    }
}
