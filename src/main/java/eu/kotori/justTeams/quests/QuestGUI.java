package eu.kotori.justTeams.quests;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

public final class QuestGUI implements InventoryHolder {
   private final JustTeams plugin;
   private final Player viewer;
   private final Team team;
   private Inventory inventory;
   private final Map<Integer, String> slotQuestIds = new HashMap<>();

   public QuestGUI(JustTeams plugin, Player viewer, Team team) {
      this.plugin = plugin;
      this.viewer = viewer;
      this.team = team;
   }

   public void open() {
      MiniMessage mm = this.plugin.getMiniMessage();
      String title = this.plugin.getMessageManager().getRawMessage("quests_gui_title");
      if (title == null || title.isEmpty()) {
         title = "<dark_gray>Team Quests";
      }

      Component titleComp = mm.deserialize(title.replace("<team>", this.team.getName()));
      this.inventory = Bukkit.createInventory(this, 54, LegacyComponentSerializer.legacySection().serialize(titleComp));
      this.render();
      this.viewer.openInventory(this.inventory);
   }

   private void render() {
      this.inventory.clear();
      this.slotQuestIds.clear();
      QuestManager qm = this.plugin.getQuestManager();
      if (qm != null) {
         List<QuestProgress> active = qm.getActiveQuests(this.team.getId());
         int[] slots = new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
         int idx = 0;

         for (QuestProgress qp : active) {
            if (idx >= slots.length) {
               break;
            }

            Quest q = qm.getQuest(qp.getQuestId());
            if (q != null) {
               this.inventory.setItem(slots[idx], this.buildItem(q, qp));
               this.slotQuestIds.put(slots[idx], qp.getQuestId());
               idx++;
            }
         }

         ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
         ItemMeta fm = filler.getItemMeta();
         if (fm != null) {
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
         }

         for (int i = 0; i < 54; i++) {
            if (this.inventory.getItem(i) == null) {
               this.inventory.setItem(i, filler);
            }
         }
      }
   }

   private ItemStack buildItem(Quest q, QuestProgress qp) {
      Material mat;
      try {
         mat = Material.valueOf(q.getIconMaterial());
      } catch (IllegalArgumentException e) {
         mat = Material.PAPER;
      }

      ItemStack item = new ItemStack(mat);
      ItemMeta meta = item.getItemMeta();
      if (meta == null) {
         return item;
      }

      MiniMessage mm = this.plugin.getMiniMessage();
      meta.setDisplayName(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("<gold>" + q.getDisplayName())));
      long progress = Math.min(qp.getProgress(), q.getRequired());
      long required = q.getRequired();
      int barLength = 20;
      int filled = (int)Math.round((double)progress / required * barLength);
      StringBuilder bar = new StringBuilder();

      for (int i = 0; i < barLength; i++) {
         bar.append(i < filled ? "<green>|" : "<dark_gray>|");
      }

      List<String> lore = new ArrayList<>();

      for (String line : q.getDescription()) {
         lore.add(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("<gray>" + line)));
      }

      lore.add(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("")));
      lore.add(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("<gray>Progress: <yellow>" + progress + "<gray>/<yellow>" + required)));
      lore.add(LegacyComponentSerializer.legacySection().serialize(mm.deserialize(bar.toString())));
      lore.add(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("")));
      if (q.getRewardPoints() > 0L) {
         lore.add(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("<gray>Reward: <gold>+" + q.getRewardPoints() + " points")));
      }

      if (q.getRewardMoney() > 0.0) {
         lore.add(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("<gray>Reward: <green>+" + q.getRewardMoney() + " coins")));
      }

      lore.add(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("")));
      if (qp.isClaimed()) {
         lore.add(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("<dark_gray>Already claimed")));
      } else if (qp.isCompleted()) {
         lore.add(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("<green>Click to claim!")));
      } else {
         lore.add(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("<dark_gray>In progress...")));
      }

      meta.setLore(lore);
      item.setItemMeta(meta);
      return item;
   }

   public void handleClick(InventoryClickEvent event) {
      event.setCancelled(true);
      int slot = event.getRawSlot();
      String questId = this.slotQuestIds.get(slot);
      if (questId != null) {
         if (event.getClick() == ClickType.LEFT) {
            QuestManager qm = this.plugin.getQuestManager();
            if (qm != null) {
               if (qm.claimReward(this.team.getId(), questId, this.viewer)) {
                  this.plugin.getMessageManager().sendRawMessage(this.viewer, "<green>Reward claimed!");
                  this.render();
               } else {
                  this.plugin.getMessageManager().sendRawMessage(this.viewer, "<red>Quest is not ready to claim or already claimed.");
               }
            }
         }
      }
   }

   @NotNull
   public Inventory getInventory() {
      return this.inventory;
   }
}
