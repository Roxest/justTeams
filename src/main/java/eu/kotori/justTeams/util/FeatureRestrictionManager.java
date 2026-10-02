package eu.kotori.justTeams.util;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class FeatureRestrictionManager {
   private final JustTeams plugin;

   public FeatureRestrictionManager(JustTeams plugin) {
      this.plugin = plugin;
   }

   public boolean isFeatureAllowed(Player player, String feature) {
      return !this.plugin.getConfigManager().isFeatureDisabledInWorld(feature, player.getWorld().getName());
   }

   private String localizeFeature(String feature) {
      if (feature != null && !feature.isEmpty()) {
         String key = "feature_names." + feature;
         if (this.plugin.getMessageManager().hasMessage(key)) {
            String localized = this.plugin.getMessageManager().getRawMessage(key);
            if (localized != null && !localized.isEmpty()) {
               return localized;
            }
         }

         String pretty = feature.replace('_', ' ');
         return pretty.substring(0, 1).toUpperCase() + pretty.substring(1);
      } else {
         return "";
      }
   }

   public boolean canAffordAndPay(Player player, String feature) {
      if (this.plugin == null || this.plugin.getConfigManager() == null) {
         return true;
      }

      if (!this.plugin.getConfigManager().isFeatureCostsEnabled()) {
         return true;
      }

      if (this.plugin.getConfigManager().isEconomyCostsEnabled()) {
         double cost = this.plugin.getConfigManager().getFeatureEconomyCost(feature);
         if (cost > 0.0) {
            Economy economy = this.plugin.getEconomy();
            if (economy == null) {
               this.plugin.getLogger().warning("Economy cost configured but Vault not found!");
               return true;
            }

            double fromBank = 0.0;
            double fromPlayer = cost;
            Team team = this.plugin.getTeamManager().getPlayerTeam(player.getUniqueId());
            if (team != null && this.plugin.getConfigManager().isBankEnabled() && team.getBalance() > 0.0) {
               fromBank = Math.min(team.getBalance(), cost);
               fromPlayer = cost - fromBank;
            }

            if (fromPlayer > 0.0) {
               if (!economy.has(player, fromPlayer)) {
                  this.plugin
                     .getMessageManager()
                     .sendMessage(
                        player,
                        "insufficient_funds",
                        Placeholder.unparsed("cost", economy.format(cost)),
                        Placeholder.unparsed("balance", economy.format(economy.getBalance(player) + fromBank))
                     );
                  return false;
               }

               if (!economy.withdrawPlayer(player, fromPlayer).transactionSuccess()) {
                  this.plugin.getMessageManager().sendMessage(player, "economy_error");
                  return false;
               }
            }

            if (team != null && fromBank > 0.0) {
               int teamId = team.getId();
               boolean bankPaid = this.plugin.getStorageManager().getStorage().withdrawFromTeamBank(teamId, fromBank);
               if (bankPaid) {
                  double authoritative = this.plugin.getStorageManager().getStorage().getTeamBalance(teamId);
                  if (authoritative >= 0.0) {
                     team.setBalance(authoritative);
                  } else {
                     team.removeBalance(fromBank);
                  }

                  double deducted = fromBank;
                  double newBalance = team.getBalance();
                  this.plugin.getTeamManager().markTeamModified(teamId);
                  this.plugin
                     .getTaskRunner()
                     .runAsync(
                        () -> this.plugin
                           .getTeamManager()
                           .publishCrossServerUpdate(teamId, "BANK_WITHDRAW", player.getUniqueId().toString(), deducted + ":" + newBalance)
                     );
               } else {
                  if (!economy.has(player, fromBank) || !economy.withdrawPlayer(player, fromBank).transactionSuccess()) {
                     if (fromPlayer > 0.0) {
                        economy.depositPlayer(player, fromPlayer);
                     }

                     this.plugin
                        .getMessageManager()
                        .sendMessage(
                           player,
                           "insufficient_funds",
                           Placeholder.unparsed("cost", economy.format(cost)),
                           Placeholder.unparsed("balance", economy.format(economy.getBalance(player)))
                        );
                     return false;
                  }

                  fromPlayer += fromBank;
                  fromBank = 0.0;
               }
            }

            if (team != null && fromBank > 0.0 && fromPlayer == 0.0) {
               if (this.plugin.getMessageManager().hasMessage("economy_charged_bank")) {
                  this.plugin
                     .getMessageManager()
                     .sendMessage(
                        player,
                        "economy_charged_bank",
                        Placeholder.unparsed("cost", economy.format(cost)),
                        Placeholder.unparsed("feature", this.localizeFeature(feature))
                     );
               } else {
                  this.plugin
                     .getMessageManager()
                     .sendMessage(
                        player,
                        "economy_charged",
                        Placeholder.unparsed("cost", economy.format(cost)),
                        Placeholder.unparsed("feature", this.localizeFeature(feature))
                     );
               }
            } else if (fromBank > 0.0 && fromPlayer > 0.0) {
               if (this.plugin.getMessageManager().hasMessage("economy_charged_split")) {
                  this.plugin
                     .getMessageManager()
                     .sendMessage(
                        player,
                        "economy_charged_split",
                        Placeholder.unparsed("cost", economy.format(cost)),
                        Placeholder.unparsed("bank", economy.format(fromBank)),
                        Placeholder.unparsed("player", economy.format(fromPlayer)),
                        Placeholder.unparsed("feature", this.localizeFeature(feature))
                     );
               } else {
                  this.plugin
                     .getMessageManager()
                     .sendMessage(
                        player,
                        "economy_charged",
                        Placeholder.unparsed("cost", economy.format(cost)),
                        Placeholder.unparsed("feature", this.localizeFeature(feature))
                     );
               }
            } else {
               this.plugin
                  .getMessageManager()
                  .sendMessage(player, "economy_charged", Placeholder.unparsed("cost", economy.format(cost)), Placeholder.unparsed("feature", feature));
            }
         }
      }

      if (this.plugin.getConfigManager().isItemCostsEnabled()) {
         List<String> itemCosts = this.plugin.getConfigManager().getFeatureItemCosts(feature);
         if (!itemCosts.isEmpty()) {
            List<ItemStack> requiredItems = this.parseItemCosts(itemCosts);
            if (!this.hasRequiredItems(player, requiredItems)) {
               this.plugin
                  .getMessageManager()
                  .sendMessage(
                     player,
                     "insufficient_items",
                     Placeholder.unparsed("required", this.formatRequiredItems(requiredItems)),
                     Placeholder.unparsed("current", "0")
                  );
               return false;
            }

            if (this.plugin.getConfigManager().shouldConsumeItemsOnUse()) {
               this.consumeItems(player, requiredItems);
               this.plugin
                  .getMessageManager()
                  .sendMessage(
                     player, "items_taken", Placeholder.unparsed("items", this.formatRequiredItems(requiredItems)), Placeholder.unparsed("feature", feature)
                  );
            }
         }
      }

      return true;
   }

   private List<ItemStack> parseItemCosts(List<String> itemCosts) {
      List<ItemStack> items = new ArrayList<>();

      for (String itemCost : itemCosts) {
         String[] parts = itemCost.split(":");
         if (parts.length != 2) {
            this.plugin.getLogger().warning("Invalid item cost format: " + itemCost + " (expected MATERIAL:AMOUNT)");
         } else {
            try {
               Material material = Material.valueOf(parts[0].toUpperCase());
               int amount = Integer.parseInt(parts[1]);
               items.add(new ItemStack(material, amount));
            } catch (IllegalArgumentException e) {
               this.plugin.getLogger().warning("Invalid material or amount in item cost: " + itemCost);
            }
         }
      }

      return items;
   }

   private boolean hasRequiredItems(Player player, List<ItemStack> requiredItems) {
      for (ItemStack required : requiredItems) {
         int playerAmount = 0;

         for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == required.getType()) {
               playerAmount += item.getAmount();
            }
         }

         if (playerAmount < required.getAmount()) {
            return false;
         }
      }

      return true;
   }

   private void consumeItems(Player player, List<ItemStack> requiredItems) {
      for (ItemStack required : requiredItems) {
         int remaining = required.getAmount();

         for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == required.getType() && remaining > 0) {
               int toRemove = Math.min(remaining, item.getAmount());
               item.setAmount(item.getAmount() - toRemove);
               remaining -= toRemove;
               if (item.getAmount() <= 0) {
                  player.getInventory().remove(item);
               }
            }
         }
      }

      player.updateInventory();
   }

   private String formatRequiredItems(List<ItemStack> items) {
      StringBuilder sb = new StringBuilder();

      for (int i = 0; i < items.size(); i++) {
         ItemStack item = items.get(i);
         sb.append(item.getAmount()).append("x ").append(this.formatMaterialName(item.getType()));
         if (i < items.size() - 1) {
            sb.append(", ");
         }
      }

      return sb.toString();
   }

   private String formatMaterialName(Material material) {
      String name = material.name().toLowerCase().replace("_", " ");
      return name.substring(0, 1).toUpperCase() + name.substring(1);
   }

   public String getFeatureCostInfo(String feature) {
      if (!this.plugin.getConfigManager().isFeatureCostsEnabled()) {
         return "";
      }

      StringBuilder info = new StringBuilder();
      if (this.plugin.getConfigManager().isEconomyCostsEnabled()) {
         double cost = this.plugin.getConfigManager().getFeatureEconomyCost(feature);
         if (cost > 0.0) {
            Economy economy = this.plugin.getEconomy();
            if (economy != null) {
               info.append("Cost: ").append(economy.format(cost));
            }
         }
      }

      if (this.plugin.getConfigManager().isItemCostsEnabled()) {
         List<String> itemCosts = this.plugin.getConfigManager().getFeatureItemCosts(feature);
         if (!itemCosts.isEmpty()) {
            List<ItemStack> requiredItems = this.parseItemCosts(itemCosts);
            if (info.length() > 0) {
               info.append(" + ");
            }

            info.append("Items: ").append(this.formatRequiredItems(requiredItems));
         }
      }

      return info.toString();
   }
}
