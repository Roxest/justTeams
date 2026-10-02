package eu.kotori.justTeams.gui;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamUpgradeManager;
import eu.kotori.justTeams.util.GuiConfigManager;
import eu.kotori.justTeams.util.ItemBuilder;
import java.text.DecimalFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class UpgradesGUI implements IRefreshableGUI, InventoryHolder {
   private final JustTeams plugin;
   private final Player viewer;
   private final Team team;
   private final Inventory inventory;

   public UpgradesGUI(JustTeams plugin, Player viewer, Team team) {
      this.plugin = plugin;
      this.viewer = viewer;
      this.team = team;
      GuiConfigManager guiManager = plugin.getGuiConfigManager();
      ConfigurationSection guiConfig = guiManager.getGUI("upgrades-gui");
      String title = this.replacePlaceholders(guiConfig.getString("title", "ᴛᴇᴀᴍ ᴜᴘɢʀᴀᴅᴇs"));
      int size = guiConfig.getInt("size", 45);
      this.inventory = Bukkit.createInventory(this, size, LegacyComponentSerializer.legacySection().serialize(plugin.getMiniMessage().deserialize(title)));
      this.initializeItems();
   }

   private void initializeItems() {
      this.inventory.clear();
      GuiConfigManager guiManager = this.plugin.getGuiConfigManager();
      ConfigurationSection guiConfig = guiManager.getGUI("upgrades-gui");
      ConfigurationSection itemsSection = guiConfig.getConfigurationSection("items");
      if (itemsSection != null) {
         TeamUpgradeManager upgrades = this.plugin.getTeamUpgradeManager();
         boolean canUpgradeMore = upgrades != null && upgrades.canUpgrade(this.team.getTier());
         boolean isOwner = this.team.isOwner(this.viewer.getUniqueId());
         this.setItemFromConfig(itemsSection, "current-tier");
         if (canUpgradeMore) {
            this.setItemFromConfig(itemsSection, "next-tier");
            if (isOwner) {
               this.setItemFromConfig(itemsSection, "upgrade-button");
            } else {
               this.setItemFromConfig(itemsSection, "upgrade-button-locked");
            }
         } else {
            this.setItemFromConfig(itemsSection, "max-tier");
         }

         this.setItemFromConfig(itemsSection, "back-button");
         ConfigurationSection fillConfig = guiConfig.getConfigurationSection("fill-item");
         if (fillConfig != null) {
            ItemStack fillItem = new ItemBuilder(Material.matchMaterial(fillConfig.getString("material", "GRAY_STAINED_GLASS_PANE")))
               .withName(fillConfig.getString("name", " "))
               .build();

            for (int i = 0; i < this.inventory.getSize(); i++) {
               if (this.inventory.getItem(i) == null) {
                  this.inventory.setItem(i, fillItem);
               }
            }
         }
      }
   }

   private void setItemFromConfig(ConfigurationSection itemsSection, String key) {
      ConfigurationSection itemConfig = itemsSection.getConfigurationSection(key);
      if (itemConfig != null) {
         if (itemConfig.getBoolean("enabled", true)) {
            int slot = itemConfig.getInt("slot", -1);
            if (slot >= 0 && slot < this.inventory.getSize()) {
               Material material = Material.matchMaterial(itemConfig.getString("material", "STONE"));
               if (material == null) {
                  material = Material.STONE;
               }

               String name = this.replacePlaceholders(itemConfig.getString("name", ""));
               List<String> lore = itemConfig.getStringList("lore").stream().map(this::replacePlaceholders).collect(Collectors.toList());
               String action = itemConfig.getString("action", key);
               this.inventory.setItem(slot, new ItemBuilder(material).withName(name).withLore(lore).withAction(action).build());
            }
         }
      }
   }

   private String replacePlaceholders(String text) {
      if (text == null) {
         return "";
      }

      TeamUpgradeManager upgrades = this.plugin.getTeamUpgradeManager();
      int current = this.team.getTier();
      int max = upgrades != null ? upgrades.getMaxTier() : current;
      int next = current + 1;
      DecimalFormat formatter = new DecimalFormat(this.plugin.getConfigManager().getCurrencyFormat());
      double cost = upgrades != null ? upgrades.getUpgradeCost(current) : -1.0;
      String nextTierLabel = upgrades != null && next > max ? "MAX" : String.valueOf(next);
      String costLabel = cost < 0.0 ? "-" : formatter.format(cost);
      return upgrades == null
         ? text.replace("<current_tier>", String.valueOf(current))
            .replace("<max_tier>", String.valueOf(max))
            .replace("<next_tier>", nextTierLabel)
            .replace("<current_max_members>", "-")
            .replace("<next_max_members>", "-")
            .replace("<current_enderchest_rows>", "-")
            .replace("<next_enderchest_rows>", "-")
            .replace("<current_damage_bonus>", "-")
            .replace("<next_damage_bonus>", "-")
            .replace("<current_home_reduction>", "-")
            .replace("<next_home_reduction>", "-")
            .replace("<upgrade_cost>", "-")
            .replace("<team_balance>", formatter.format(this.team.getBalance()))
         : text.replace("<current_tier>", String.valueOf(current))
            .replace("<max_tier>", String.valueOf(max))
            .replace("<next_tier>", nextTierLabel)
            .replace("<current_max_members>", String.valueOf(upgrades.getMaxMembers(current)))
            .replace("<next_max_members>", next <= max ? String.valueOf(upgrades.getMaxMembers(next)) : "-")
            .replace("<current_enderchest_rows>", String.valueOf(upgrades.getEnderChestRows(current)))
            .replace("<next_enderchest_rows>", next <= max ? String.valueOf(upgrades.getEnderChestRows(next)) : "-")
            .replace("<current_damage_bonus>", upgrades.getDamageBonusPercent(current) + "%")
            .replace("<next_damage_bonus>", next <= max ? upgrades.getDamageBonusPercent(next) + "%" : "-")
            .replace("<current_home_reduction>", upgrades.getHomeCooldownReductionPercent(current) + "%")
            .replace("<next_home_reduction>", next <= max ? upgrades.getHomeCooldownReductionPercent(next) + "%" : "-")
            .replace("<upgrade_cost>", costLabel)
            .replace("<team_balance>", formatter.format(this.team.getBalance()));
   }

   @Override
   public void open() {
      this.viewer.openInventory(this.inventory);
   }

   @Override
   public void refresh() {
      this.initializeItems();
   }

   public Player getViewer() {
      return this.viewer;
   }

   public Team getTeam() {
      return this.team;
   }

   @NotNull
   public Inventory getInventory() {
      return this.inventory;
   }
}
