package eu.kotori.justTeams.gui;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.gui.admin.AdminGUI;
import eu.kotori.justTeams.gui.admin.AdminMemberEditGUI;
import eu.kotori.justTeams.gui.admin.AdminTeamListGUI;
import eu.kotori.justTeams.gui.admin.AdminTeamManageGUI;
import eu.kotori.justTeams.gui.sub.TeamSettingsGUI;
import eu.kotori.justTeams.quests.QuestGUI;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamManager;
import eu.kotori.justTeams.team.TeamPlayer;
import eu.kotori.justTeams.team.TeamRole;
import eu.kotori.justTeams.util.EffectsUtil;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class TeamGUIListener implements Listener {
   private final JustTeams plugin;
   private final TeamManager teamManager;
   private final NamespacedKey actionKey;
   private final ConcurrentHashMap<String, Long> actionCooldowns = new ConcurrentHashMap<>();
   private final Object actionLock = new Object();

   public TeamGUIListener(JustTeams plugin) {
      this.plugin = plugin;
      this.teamManager = plugin.getTeamManager();
      this.actionKey = JustTeams.getActionKey();
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent event) {
      String prefix = event.getPlayer().getUniqueId() + ":";
      this.actionCooldowns.keySet().removeIf(k -> k.startsWith(prefix));
   }

   private boolean checkActionCooldown(Player player, String action, long cooldownMs) {
      if (player != null && action != null) {
         String key = player.getUniqueId() + ":" + action;
         long currentTime = System.currentTimeMillis();
         synchronized (this.actionLock) {
            return this.actionCooldowns
                  .compute(
                     key, (k, lastActionTime) -> (Long)(lastActionTime != null && currentTime - lastActionTime < cooldownMs ? lastActionTime : currentTime)
                  )
               == currentTime;
         }
      } else {
         return false;
      }
   }

   @EventHandler
   public void onGUIClick(InventoryClickEvent event) {
      if (event.getWhoClicked() instanceof Player player) {
         try {
            InventoryHolder holder = event.getView().getTopInventory().getHolder();
            boolean isOurGui = holder instanceof IRefreshableGUI
               || holder instanceof NoTeamGUI
               || holder instanceof ConfirmGUI
               || holder instanceof AdminGUI
               || holder instanceof AdminTeamListGUI
               || holder instanceof AdminTeamManageGUI
               || holder instanceof AdminMemberEditGUI
               || holder instanceof TeamSettingsGUI
               || holder instanceof LeaderboardCategoryGUI
               || holder instanceof LeaderboardViewGUI
               || holder instanceof JoinRequestGUI
               || holder instanceof InvitesGUI
               || holder instanceof WarpsGUI
               || holder instanceof BlacklistGUI
               || holder instanceof TeamCreationColorGUI
               || holder instanceof AllyGUI
               || holder instanceof UpgradesGUI
               || holder instanceof QuestGUI;
            if (holder instanceof QuestGUI questGui) {
               questGui.handleClick(event);
               return;
            }

            if (!isOurGui) {
               return;
            }

            event.setCancelled(true);
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
               return;
            }

            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() == Material.AIR) {
               return;
            }

            ItemMeta meta = clickedItem.getItemMeta();
            if (meta == null) {
               return;
            }

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (holder instanceof BlacklistGUI && this.plugin.getConfigManager().isDebugEnabled()) {
               this.plugin.getDebugLogger().log("=== BLACKLIST GUI MAIN CLICK DEBUG ===");
               this.plugin.getDebugLogger().log("Player: " + player.getName());
               this.plugin.getDebugLogger().log("Clicked item type: " + clickedItem.getType());
               this.plugin.getDebugLogger().log("Clicked item has meta: " + (meta != null));
               this.plugin.getDebugLogger().log("PDC has action key: " + pdc.has(this.actionKey, PersistentDataType.STRING));
               if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
                  String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
                  this.plugin.getDebugLogger().log("Action found: " + action);
               } else {
                  this.plugin.getLogger().warning("No action key found in blacklist item!");

                  for (NamespacedKey key : pdc.getKeys()) {
                     this.plugin.getDebugLogger().log("PDC key found: " + key.toString());
                  }
               }

               this.plugin.getDebugLogger().log("=== END BLACKLIST GUI MAIN CLICK DEBUG ===");
            }

            if (!pdc.has(this.actionKey, PersistentDataType.STRING)) {
               this.plugin.getDebugLogger().log("GUI click without valid action key from " + player.getName());
               return;
            }

            String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
            if (action == null || action.isEmpty() || action.length() > 50) {
               this.plugin.getDebugLogger().log("Invalid action in GUI click from " + player.getName() + ": " + action);
               return;
            }

            if ("back-button".equals(action)) {
               this.plugin.getDebugLogger().log("Back button clicked by " + player.getName() + " in " + holder.getClass().getSimpleName());
            }

            if (holder instanceof TeamGUI gui) {
               this.onTeamGUIClick(player, gui, clickedItem, pdc, event.getClick());
            } else if (holder instanceof MemberEditGUI gui) {
               this.onMemberEditGUIClick(player, gui, pdc);
            } else if (holder instanceof BankGUI gui) {
               this.onBankGUIClick(player, gui, pdc);
            } else if (holder instanceof UpgradesGUI gui) {
               this.onUpgradesGUIClick(player, gui, pdc);
            } else if (holder instanceof TeamSettingsGUI gui) {
               this.onTeamSettingsGUIClick(player, gui, pdc);
            } else if (holder instanceof JoinRequestGUI gui) {
               this.onJoinRequestGUIClick(player, gui, event.getClick(), clickedItem);
            } else if (holder instanceof InvitesGUI gui) {
               this.onInvitesGUIClick(player, gui, event.getClick(), clickedItem);
            } else if (holder instanceof LeaderboardCategoryGUI) {
               this.onLeaderboardCategoryGUIClick(player, pdc);
            } else if (holder instanceof LeaderboardViewGUI) {
               this.onLeaderboardViewGUIClick(player, pdc);
            } else if (holder instanceof NoTeamGUI) {
               this.onNoTeamGUIClick(player, pdc);
            } else if (holder instanceof AdminGUI) {
               this.onAdminGUIClick(player, pdc);
            } else if (holder instanceof AdminTeamListGUI gui) {
               this.onAdminTeamListGUIClick(player, gui, clickedItem, pdc);
            } else if (holder instanceof AdminTeamManageGUI gui) {
               this.onAdminTeamManageGUIClick(player, gui, pdc);
            } else if (holder instanceof AdminMemberEditGUI gui) {
               this.onAdminMemberEditGUIClick(player, gui, pdc);
            } else if (holder instanceof ConfirmGUI gui) {
               this.onConfirmGUIClick(gui, pdc);
            } else if (holder instanceof WarpsGUI) {
               this.onWarpsGUIClick(player, (WarpsGUI)holder, event.getClick(), clickedItem, pdc);
            } else if (holder instanceof BlacklistGUI gui) {
               this.onBlacklistGUIClick(player, gui, event.getClick(), clickedItem, pdc);
            } else if (holder instanceof TeamCreationColorGUI gui) {
               this.onTeamCreationColorGUIClick(player, gui, pdc);
            } else if (holder instanceof AllyGUI gui) {
               this.onAllyGUIClick(player, gui, pdc);
            }
         } catch (Exception e) {
            this.plugin.getLogger().severe("Error handling GUI click for " + player.getName() + ": " + e.getMessage());
            if (this.plugin.getConfigManager().isDebugEnabled()) {
               this.plugin.getLogger().severe("Error in GUI action: " + e.getMessage());
            }

            this.plugin.getMessageManager().sendMessage(player, "gui_error");
            event.setCancelled(true);
         }
      }
   }

   private void onTeamGUIClick(Player player, TeamGUI gui, ItemStack clickedItem, PersistentDataContainer pdc, ClickType click) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         Team team = gui.getTeam();
         if (team == null) {
            this.plugin.getDebugLogger().log("TeamGUI click with null team for " + player.getName());
         } else if (!team.isMember(player.getUniqueId())) {
            this.plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            player.closeInventory();
         } else {
            switch (action) {
               case "player-head":
                  if (clickedItem.getItemMeta() instanceof SkullMeta skullMeta && skullMeta.getOwnerProfile() != null) {
                     UUID targetUuid = skullMeta.getOwnerProfile().getUniqueId();

                     if (targetUuid != null) {
                        if (targetUuid.equals(player.getUniqueId())) {
                           this.plugin.getMessageManager().sendMessage(player, "cannot_edit_own_permissions");
                           return;
                        }

                        TeamPlayer viewerMember = team.getMember(player.getUniqueId());
                        TeamPlayer targetMember = team.getMember(targetUuid);
                        if (viewerMember == null || targetMember == null) {
                           this.plugin.getMessageManager().sendMessage(player, "player_not_in_team");
                           return;
                        }

                        boolean canEdit = false;
                        if (viewerMember.getRole() == TeamRole.OWNER) {
                           canEdit = true;
                        } else if (viewerMember.getRole() == TeamRole.CO_OWNER) {
                           canEdit = targetMember.getRole() == TeamRole.MEMBER;
                        }

                        if (canEdit) {
                           new MemberEditGUI(this.plugin, team, player, targetUuid).open();
                        } else {
                           this.plugin.getMessageManager().sendMessage(player, "no_permission");
                        }
                     }
                  }
                  break;
               case "join-requests":
                  new JoinRequestGUI(this.plugin, player, team).open();
                  break;
               case "join-requests-locked":
                  this.plugin.getMessageManager().sendMessage(player, "join_requests_permission_denied");
                  break;
               case "warps":
                  try {
                     Class.forName("eu.kotori.justTeams.gui.WarpsGUI");
                     this.teamManager.openWarpsGUI(player);
                  } catch (ClassNotFoundException e) {
                     this.teamManager.listTeamWarps(player);
                  }
                  break;
               case "allies":
                  this.teamManager.openAllyGUI(player);
                  break;
               case "bank":
                  TeamPlayer viewerMember = team.getMember(player.getUniqueId());
                  if (viewerMember == null || !viewerMember.canWithdraw() && !player.hasPermission("justteams.bypass.bank.use")) {
                     this.plugin.getMessageManager().sendMessage(player, "no_permission");
                     EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                     return;
                  }

                  new BankGUI(this.plugin, player, team).open();
                  break;
               case "bank-locked":
                  this.plugin.getMessageManager().sendMessage(player, "bank_permission_denied");
                  break;
               case "home":
                  TeamPlayer homeMember = team.getMember(player.getUniqueId());
                  if (homeMember == null || !homeMember.canUseHome() && !player.hasPermission("justteams.bypass.home.use")) {
                     this.plugin.getMessageManager().sendMessage(player, "no_permission");
                     EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                     return;
                  }

                  if (!this.checkActionCooldown(player, "home", 5000L)) {
                     return;
                  }

                  this.teamManager.teleportToHome(player);
                  break;
               case "ender-chest":
                  if (this.plugin.getConfigManager().isDebugEnabled()) {
                     this.plugin.getLogger().info("Enderchest clicked by " + player.getName() + " in team " + team.getName());
                  }

                  TeamPlayer ecMember = team.getMember(player.getUniqueId());
                  if (ecMember == null) {
                     this.plugin.getLogger().warning("Player " + player.getName() + " not found in team " + team.getName());
                     this.plugin.getMessageManager().sendMessage(player, "player_not_in_team");
                     return;
                  }

                  boolean hasPermission = ecMember.canUseEnderChest();
                  boolean hasBypass = player.hasPermission("justteams.bypass.enderchest.use");
                  if (this.plugin.getConfigManager().isDebugEnabled()) {
                     this.plugin
                        .getLogger()
                        .info(
                           "Enderchest permission check for "
                              + player.getName()
                              + " - canUseEnderChest: "
                              + hasPermission
                              + ", hasBypass: "
                              + hasBypass
                              + ", member: "
                              + ecMember.getPlayerUuid()
                              + ", team: "
                              + team.getName()
                              + ", teamId: "
                              + team.getId()
                        );
                  }

                  if (!hasPermission && !hasBypass) {
                     this.plugin.getLogger().warning("Player " + player.getName() + " attempted to access enderchest without permission!");
                     this.plugin.getLogger().warning("Permission details - canUseEnderChest: " + hasPermission + ", hasBypass: " + hasBypass);
                     this.plugin.getMessageManager().sendMessage(player, "no_permission");
                     EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                     return;
                  }

                  if (!this.checkActionCooldown(player, "enderchest", 2000L)) {
                     if (this.plugin.getConfigManager().isDebugEnabled()) {
                        this.plugin.getLogger().info("Enderchest action blocked by cooldown for " + player.getName());
                     }

                     return;
                  }

                  if (this.plugin.getConfigManager().isDebugEnabled()) {
                     this.plugin.getLogger().info("Opening enderchest for " + player.getName());
                  }

                  this.teamManager.openEnderChest(player);
                  break;
               case "ender-chest-locked":
                  if (this.plugin.getConfigManager().isDebugEnabled()) {
                     this.plugin.getLogger().info("Enderchest-locked clicked by " + player.getName() + " in team " + team.getName());
                  }

                  this.plugin.getMessageManager().sendMessage(player, "enderchest_permission_denied");
                  EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                  break;
               case "sort":
                  gui.cycleSort(click.isLeftClick());
                  break;
               case "pvp-toggle":
                  if (!this.checkActionCooldown(player, "pvp-toggle", 2000L)) {
                     return;
                  }

                  this.teamManager.togglePvpStatus(player);
                  gui.initializeItems();
                  break;
               case "team-settings":
                  new TeamSettingsGUI(this.plugin, player, team).open();
                  break;
               case "upgrades-button":
                  if (this.plugin.getTeamUpgradeManager() == null || !this.plugin.getTeamUpgradeManager().isEnabled()) {
                     this.plugin.getMessageManager().sendMessage(player, "team_upgrades_disabled");
                     return;
                  }

                  new UpgradesGUI(this.plugin, player, team).open();
                  break;
               case "settings":
                  new TeamSettingsGUI(this.plugin, player, team).open();
                  break;
               case "settings-locked":
                  this.plugin.getMessageManager().sendMessage(player, "settings_permission_denied");
                  break;
               case "disband-button":
                  if (!this.checkActionCooldown(player, "disband", 10000L)) {
                     return;
                  }

                  this.teamManager.disbandTeam(player);
                  break;
               case "leave-button":
                  if (!this.checkActionCooldown(player, "leave", 5000L)) {
                     return;
                  }

                  new ConfirmGUI(this.plugin, player, Component.text("Are you sure you want to leave the team?"), confirmed -> {
                     if (confirmed) {
                        this.teamManager.leaveTeam(player);
                     }
                  }).open();
                  break;
               case "blacklist":
                  new BlacklistGUI(this.plugin, team, player).open();
                  break;
               default:
                  this.plugin.getDebugLogger().log("Unknown TeamGUI action: " + action + " from " + player.getName());
            }
         }
      }
   }

   private void onMemberEditGUIClick(Player player, MemberEditGUI gui, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         TeamPlayer targetMember = gui.getTargetMember();
         if (targetMember == null) {
            player.closeInventory();
         } else {
            switch (action) {
               case "promote-button":
                  if (!this.checkActionCooldown(player, "promote", 2000L)) {
                     return;
                  }

                  this.teamManager.promotePlayer(player, gui.getTargetUuid());
                  break;
               case "demote-button":
                  if (!this.checkActionCooldown(player, "demote", 2000L)) {
                     return;
                  }

                  this.teamManager.demotePlayer(player, gui.getTargetUuid());
                  break;
               case "kick-button":
                  if (!this.checkActionCooldown(player, "kick", 2000L)) {
                     return;
                  }

                  this.teamManager.kickPlayer(player, gui.getTargetUuid());
                  break;
               case "transfer-button":
                  if (!this.checkActionCooldown(player, "transfer", 5000L)) {
                     return;
                  }

                  this.teamManager.transferOwnership(player, gui.getTargetUuid());
                  break;
               case "back-button":
                  new TeamGUI(this.plugin, gui.getTeam(), player).open();
                  break;
               case "withdraw-permission":
               case "enderchest-permission":
               case "sethome-permission":
               case "usehome-permission":
                  if (!this.checkActionCooldown(player, "permission-change", 1000L)) {
                     return;
                  }

                  boolean isSelfView = gui.getTargetUuid().equals(player.getUniqueId());
                  if (isSelfView) {
                     this.plugin.getMessageManager().sendMessage(player, "cannot_edit_own_permissions");
                     return;
                  }

                  boolean canWithdraw = targetMember.canWithdraw();
                  boolean canUseEC = targetMember.canUseEnderChest();
                  boolean canSetHome = targetMember.canSetHome();
                  boolean canUseHome = targetMember.canUseHome();
                  switch (action) {
                     case "withdraw-permission":
                        canWithdraw = !canWithdraw;
                        break;
                     case "enderchest-permission":
                        canUseEC = !canUseEC;
                        break;
                     case "sethome-permission":
                        canSetHome = !canSetHome;
                        break;
                     case "usehome-permission":
                        canUseHome = !canUseHome;
                  }

                  this.teamManager.updateMemberPermissions(player, targetMember.getPlayerUuid(), canWithdraw, canUseEC, canSetHome, canUseHome);
                  break;
               case "withdraw-permission-view":
               case "enderchest-permission-view":
               case "sethome-permission-view":
               case "usehome-permission-view":
                  this.plugin.getMessageManager().sendMessage(player, "view_only_mode");
                  break;
               case "toggle-promote-co-owner":
                  if (!this.checkActionCooldown(player, "permission-change", 1000L)) {
                     return;
                  }

                  Team team = gui.getTeam();
                  if (!team.isOwner(player.getUniqueId())) {
                     this.plugin.getMessageManager().sendMessage(player, "not_owner");
                     EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                     return;
                  }

                  if (targetMember.getRole() != TeamRole.CO_OWNER) {
                     this.plugin.getMessageManager().sendRawMessage(player, "<red>This permission can only be granted to Co-owners.");
                     EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                     return;
                  }

                  boolean pcNewValue = !targetMember.canPromoteToCoOwner();
                  targetMember.setCanPromoteToCoOwner(pcNewValue);

                  try {
                     this.plugin
                        .getStorageManager()
                        .getStorage()
                        .updateMemberPermission(team.getId(), targetMember.getPlayerUuid(), "promote_co_owner", pcNewValue);
                  } catch (Exception e) {
                     this.plugin.getLogger().severe("Failed to update promote-co-owner permission: " + e.getMessage());
                     targetMember.setCanPromoteToCoOwner(!pcNewValue);
                     EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                     return;
                  }

                  this.teamManager
                     .publishCrossServerUpdate(
                        team.getId(),
                        "ADMIN_PERMISSION_UPDATE",
                        player.getUniqueId().toString(),
                        targetMember.getPlayerUuid() + ":promote_co_owner:" + pcNewValue
                     );
                  this.plugin
                     .getMessageManager()
                     .sendRawMessage(player, (pcNewValue ? "<green>Enabled" : "<red>Disabled") + " <white>promote-to-co-owner permission");
                  EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
                  break;
               default:
                  return;
            }

            gui.initializeItems();
         }
      }
   }

   private void onUpgradesGUIClick(Player player, UpgradesGUI gui, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         if (action != null) {
            switch (action) {
               case "back-button":
                  new TeamGUI(this.plugin, gui.getTeam(), player).open();
                  break;
               case "upgrade-purchase":
                  if (!this.checkActionCooldown(player, "upgrade-purchase", 1500L)) {
                     return;
                  }

                  if (!gui.getTeam().isOwner(player.getUniqueId())) {
                     this.plugin.getMessageManager().sendMessage(player, "not_owner");
                     EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                     return;
                  }

                  this.plugin.getTeamManager().tryUpgradeTeamTier(player);
                  break;
               case "upgrade-locked":
                  this.plugin.getMessageManager().sendMessage(player, "not_owner");
                  EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            }
         }
      }
   }

   private void onBankGUIClick(Player player, BankGUI gui, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         switch (action) {
            case "back-button":
               new TeamGUI(this.plugin, gui.getTeam(), player).open();
               break;
            case "withdraw-locked":
               this.plugin.getMessageManager().sendMessage(player, "gui_action_locked");
               EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
               break;
            case "deposit":
            case "withdraw":
               if (!this.checkActionCooldown(player, "bank-action", 1000L)) {
                  return;
               }

               player.closeInventory();
               boolean isDeposit = action.equals("deposit");
               String promptAction = isDeposit ? "deposit" : "withdraw";
               String prompt = this.plugin.getMessageManager().getRawMessage("prompt_bank_amount").replace("<action>", promptAction);
               this.plugin.getMessageManager().sendRawMessage(player, prompt);
               this.plugin.getChatInputManager().awaitInput(player, gui, input -> {
                  try {
                     double amount = Double.parseDouble(input);
                     if (amount <= 0.0) {
                        this.plugin.getMessageManager().sendMessage(player, "bank_invalid_amount");
                     } else if (amount > 1.0E9) {
                        this.plugin.getMessageManager().sendMessage(player, "bank_amount_too_large");
                     } else if (isDeposit) {
                        this.teamManager.deposit(player, amount);
                     } else {
                        this.teamManager.withdraw(player, amount);
                     }
                  } catch (NumberFormatException e) {
                     this.plugin.getMessageManager().sendMessage(player, "bank_invalid_amount");
                  }

                  this.plugin.getTaskRunner().runOnEntity(player, gui::open);
               });
         }
      }
   }

   private void onTeamSettingsGUIClick(Player player, TeamSettingsGUI gui, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         switch (action) {
            case "back-button":
               new TeamGUI(this.plugin, gui.getTeam(), player).open();
               break;
            case "toggle-public":
               if (!this.checkActionCooldown(player, "toggle-public", 2000L)) {
                  return;
               }

               this.plugin.getTeamManager().togglePublicStatus(player);
               gui.initializeItems();
               break;
            case "change-tag":
            case "change-description":
               boolean isTag = action.equals("change-tag");
               if (isTag && !this.plugin.getConfigManager().isTeamTagEnabled()) {
                  this.plugin.getMessageManager().sendMessage(player, "feature_disabled");
                  EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                  return;
               }

               String actionType = action.equals("change-tag") ? "change-tag" : "change-description";
               if (!this.checkActionCooldown(player, actionType, 2000L)) {
                  return;
               }

               player.closeInventory();
               String setting = isTag ? "tag" : "description";
               String prompt = this.plugin.getMessageManager().getRawMessage("prompt_setting_change").replace("<setting>", setting);
               this.plugin.getMessageManager().sendRawMessage(player, prompt);
               this.plugin.getChatInputManager().awaitInput(player, gui, input -> {
                  if (isTag) {
                     this.teamManager.setTeamTag(player, input);
                  } else {
                     this.teamManager.setTeamDescription(player, input);
                  }

                  this.plugin.getTaskRunner().runOnEntity(player, gui::open);
               });
         }
      }
   }

   private void onJoinRequestGUIClick(Player player, JoinRequestGUI gui, ClickType click, ItemStack clickedItem) {
      if (clickedItem != null) {
         ItemMeta meta = clickedItem.getItemMeta();
         if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
               String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
               if (action.equals("back-button")) {
                  new TeamGUI(this.plugin, gui.getTeam(), player).open();
               } else if (action.equals("player-head")) {
                  String playerUuidStr = (String)pdc.get(new NamespacedKey(JustTeams.getInstance(), "player_uuid"), PersistentDataType.STRING);
                  if (playerUuidStr != null) {
                     try {
                        UUID targetUuid = UUID.fromString(playerUuidStr);
                        if (click.isLeftClick()) {
                           this.teamManager.acceptJoinRequest(gui.getTeam(), targetUuid);
                        } else if (click.isRightClick()) {
                           this.teamManager.denyJoinRequest(gui.getTeam(), targetUuid);
                        }

                        gui.initializeItems();
                     } catch (IllegalArgumentException e) {
                        this.plugin.getLogger().warning("Invalid UUID in join request GUI: " + playerUuidStr);
                     }
                  }
               }
            }
         }
      }
   }

   private void onInvitesGUIClick(Player player, InvitesGUI gui, ClickType click, ItemStack clickedItem) {
      if (clickedItem != null) {
         ItemMeta meta = clickedItem.getItemMeta();
         if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
               String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
               switch (action) {
                  case "back-button":
                     Team team = this.teamManager.getPlayerTeam(player.getUniqueId());
                     if (team != null) {
                        new TeamGUI(this.plugin, team, player).open();
                     } else {
                        new NoTeamGUI(this.plugin, player).open();
                     }
                     break;
                  case "close-button":
                     player.closeInventory();
                     break;
                  case "team-icon":
                     String teamIdStr = (String)pdc.get(new NamespacedKey(this.plugin, "team_id"), PersistentDataType.STRING);
                     String teamName = (String)pdc.get(new NamespacedKey(this.plugin, "team_name"), PersistentDataType.STRING);
                     if (teamIdStr != null && teamName != null) {
                        try {
                           int teamId = Integer.parseInt(teamIdStr);
                           if (click.isLeftClick()) {
                              player.closeInventory();
                              this.teamManager.acceptInvite(player, teamName);
                           } else if (click.isRightClick()) {
                              player.closeInventory();
                              this.teamManager.denyInvite(player, teamName);
                           }
                        } catch (NumberFormatException e) {
                           this.plugin.getLogger().warning("Invalid team ID in invites GUI: " + teamIdStr);
                        }
                     }
               }
            }
         }
      }
   }

   private void onLeaderboardCategoryGUIClick(Player player, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         if ("back-button".equals(action)) {
            this.plugin.getTaskRunner().runAsync(() -> {
               Team team = this.teamManager.getPlayerTeam(player.getUniqueId());
               if (team != null) {
                  this.plugin.getTaskRunner().runOnEntity(player, () -> new TeamGUI(this.plugin, team, player).open());
               } else {
                  this.plugin.getTaskRunner().runOnEntity(player, () -> new NoTeamGUI(this.plugin, player).open());
               }
            });
         } else {
            LeaderboardViewGUI.LeaderboardType type;
            String title;
            switch (action) {
               case "top-kills":
                  type = LeaderboardViewGUI.LeaderboardType.KILLS;
                  title = this.plugin.getMessageManager().getRawMessage("leaderboard_title_kills");
                  break;
               case "top-balance":
                  type = LeaderboardViewGUI.LeaderboardType.BALANCE;
                  title = this.plugin.getMessageManager().getRawMessage("leaderboard_title_balance");
                  break;
               case "top-members":
                  type = LeaderboardViewGUI.LeaderboardType.MEMBERS;
                  title = this.plugin.getMessageManager().getRawMessage("leaderboard_title_members");
                  break;
               default:
                  return;
            }

            this.plugin.getTaskRunner().runAsync(() -> {
               Map topTeams = switch (type) {
                  case KILLS -> this.plugin.getStorageManager().getStorage().getTopTeamsByKills(28);
                  case BALANCE -> this.plugin.getStorageManager().getStorage().getTopTeamsByBalance(28);
                  case MEMBERS -> this.plugin.getStorageManager().getStorage().getTopTeamsByMembers(28);
                  default -> Map.of();
               };
               Map<Integer, Team> finalTopTeams = topTeams;
               this.plugin.getTaskRunner().runOnEntity(player, () -> new LeaderboardViewGUI(this.plugin, player, title, finalTopTeams, type).open());
            });
         }
      }
   }

   private void onLeaderboardViewGUIClick(Player player, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         if (action.equals("back-button")) {
            new LeaderboardCategoryGUI(this.plugin, player).open();
         }
      }
   }

   private void onNoTeamGUIClick(Player player, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         switch (action) {
            case "create-team":
               player.closeInventory();
               this.plugin.getMessageManager().sendMessage(player, "prompt_team_name");
               this.plugin
                  .getChatInputManager()
                  .awaitInput(
                     player,
                     null,
                     teamName -> {
                        if (teamName != null && !teamName.trim().isEmpty() && !teamName.equalsIgnoreCase("cancel")) {
                           String validationError = this.teamManager.validateTeamName(teamName);
                           if (validationError != null) {
                              this.plugin.getMessageManager().sendRawMessage(player, this.plugin.getMessageManager().getRawMessage("prefix") + validationError);
                              this.plugin.getTaskRunner().runOnEntity(player, () -> new NoTeamGUI(this.plugin, player).open());
                           } else {
                              if (this.plugin.getConfigManager().isTeamTagEnabled()) {
                                 this.plugin.getMessageManager().sendMessage(player, "prompt_team_tag");
                                 this.plugin
                                    .getChatInputManager()
                                    .awaitInput(
                                       player,
                                       null,
                                       teamTag -> {
                                          if (teamTag != null && !teamTag.trim().isEmpty() && !teamTag.equalsIgnoreCase("cancel")) {
                                             if (this.plugin.getConfigManager().isGuiColorSelectionEnabled()) {
                                                this.plugin
                                                   .getTaskRunner()
                                                   .runOnEntity(player, () -> new TeamCreationColorGUI(this.plugin, player, teamName, teamTag).open());
                                             } else if (this.plugin.getConfigManager().isCreationRequireColor()) {
                                                this.plugin.getMessageManager().sendMessage(player, "prompt_team_color");
                                                this.plugin.getChatInputManager().awaitInput(player, null, teamColor -> {
                                                   if (teamColor != null && !teamColor.trim().isEmpty() && !teamColor.equalsIgnoreCase("cancel")) {
                                                      this.teamManager.createTeamWithColor(player, teamName, teamTag, teamColor);
                                                   } else {
                                                      this.plugin.getMessageManager().sendMessage(player, "creation_cancelled");
                                                      this.plugin.getTaskRunner().runOnEntity(player, () -> new NoTeamGUI(this.plugin, player).open());
                                                   }
                                                });
                                             } else {
                                                this.teamManager
                                                   .createTeamWithColor(player, teamName, teamTag, this.plugin.getConfigManager().getCreationDefaultColor());
                                             }
                                          } else {
                                             this.plugin.getMessageManager().sendMessage(player, "creation_cancelled");
                                             this.plugin.getTaskRunner().runOnEntity(player, () -> new NoTeamGUI(this.plugin, player).open());
                                          }
                                       }
                                    );
                              } else if (this.plugin.getConfigManager().isGuiColorSelectionEnabled()) {
                                 this.plugin.getTaskRunner().runOnEntity(player, () -> new TeamCreationColorGUI(this.plugin, player, teamName, "").open());
                              } else if (this.plugin.getConfigManager().isCreationRequireColor()) {
                                 this.plugin.getMessageManager().sendMessage(player, "prompt_team_color");
                                 this.plugin.getChatInputManager().awaitInput(player, null, teamColor -> {
                                    if (teamColor != null && !teamColor.trim().isEmpty() && !teamColor.equalsIgnoreCase("cancel")) {
                                       this.teamManager.createTeamWithColor(player, teamName, "", teamColor);
                                    } else {
                                       this.plugin.getMessageManager().sendMessage(player, "creation_cancelled");
                                       this.plugin.getTaskRunner().runOnEntity(player, () -> new NoTeamGUI(this.plugin, player).open());
                                    }
                                 });
                              } else {
                                 this.teamManager.createTeamWithColor(player, teamName, "", this.plugin.getConfigManager().getCreationDefaultColor());
                              }
                           }
                        } else {
                           this.plugin.getMessageManager().sendMessage(player, "creation_cancelled");
                           this.plugin.getTaskRunner().runOnEntity(player, () -> new NoTeamGUI(this.plugin, player).open());
                        }
                     }
                  );
               break;
            case "leaderboards":
               new LeaderboardCategoryGUI(this.plugin, player).open();
         }
      }
   }

   private void onTeamCreationColorGUIClick(Player player, TeamCreationColorGUI gui, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         if ("cancel-creation".equals(action)) {
            player.closeInventory();
            this.plugin.getMessageManager().sendMessage(player, "creation_cancelled");
            this.plugin.getTaskRunner().runOnEntity(player, () -> new NoTeamGUI(this.plugin, player).open());
         } else {
            if (action.startsWith("color:")) {
               String colorName = action.substring(6);
               String teamName = gui.getTeamName();
               String teamTag = gui.getTeamTag();
               player.closeInventory();
               this.teamManager.createTeamWithColor(player, teamName, teamTag, colorName);
            }
         }
      }
   }

   private void onAdminGUIClick(Player player, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         switch (action) {
            case "back-button":
            case "close":
               player.closeInventory();
               break;
            case "manage-teams":
               this.plugin.getTaskRunner().runAsync(() -> {
                  List<Team> allTeams = this.teamManager.getAllTeams();
                  this.plugin.getTaskRunner().runOnEntity(player, () -> new AdminTeamListGUI(this.plugin, player, allTeams, 0).open());
               });
               break;
            case "view-enderchest":
               player.closeInventory();
               this.plugin.getChatInputManager().awaitInput(player, null, input -> {
                  if (input != null && !input.trim().isEmpty()) {
                     this.teamManager.adminOpenEnderChest(player, input.trim());
                  } else {
                     this.plugin.getMessageManager().sendMessage(player, "invalid_input");
                  }
               });
               this.plugin.getMessageManager().sendMessage(player, "admin_enderchest_input_prompt");
               break;
            case "reload-plugin":
               player.closeInventory();
               this.plugin.getTaskRunner().runAsync(() -> {
                  try {
                     this.plugin.getConfigManager().reloadConfig();
                     this.plugin.getTaskRunner().runOnEntity(player, () -> {
                        this.plugin.getMessageManager().sendMessage(player, "admin_reload_success");
                        EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
                     });
                  } catch (Exception e) {
                     this.plugin.getTaskRunner().runOnEntity(player, () -> {
                        this.plugin.getMessageManager().sendMessage(player, "admin_reload_failed");
                        EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                     });
                  }
               });
               break;
            default:
               this.plugin.getDebugLogger().log("Unknown admin GUI action: " + action + " from " + player.getName());
         }
      }
   }

   private void onAdminTeamListGUIClick(Player player, AdminTeamListGUI gui, ItemStack clickedItem, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         switch (action) {
            case "next-page":
               new AdminTeamListGUI(this.plugin, player, gui.getAllTeams(), gui.getPage() + 1).open();
               break;
            case "previous-page":
               new AdminTeamListGUI(this.plugin, player, gui.getAllTeams(), gui.getPage() - 1).open();
               break;
            case "back-button":
               new AdminGUI(this.plugin, player).open();
               break;
            case "team-head":
               String legacyDisplayName = clickedItem.getItemMeta().getDisplayName();
               if (legacyDisplayName == null || legacyDisplayName.isEmpty()) {
                  return;
               }

               String plainName = org.bukkit.ChatColor.stripColor(legacyDisplayName);
               this.plugin.getTaskRunner().runAsync(() -> {
                  Team targetTeam = this.teamManager.getTeamByName(plainName);
                  if (targetTeam != null) {
                     this.plugin.getTaskRunner().runOnEntity(player, () -> new AdminTeamManageGUI(this.plugin, player, targetTeam).open());
                  }
               });
         }
      }
   }

   private void onAdminTeamManageGUIClick(Player player, AdminTeamManageGUI gui, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         Team team = gui.getTargetTeam();
         switch (action) {
            case "back-button":
               this.plugin.getTaskRunner().runAsync(() -> {
                  List<Team> allTeams = this.teamManager.getAllTeams();
                  this.plugin.getTaskRunner().runOnEntity(player, () -> new AdminTeamListGUI(this.plugin, player, allTeams, 0).open());
               });
               break;
            case "disband-team":
               this.teamManager.adminDisbandTeam(player, team.getName());
               break;
            case "rename-team":
               player.closeInventory();
               this.plugin
                  .getChatInputManager()
                  .awaitInput(
                     player,
                     null,
                     input -> {
                        if (input != null && !input.trim().isEmpty()) {
                           String newName = input.trim();
                           int minLength = this.plugin.getConfigManager().getMinNameLength();
                           int maxLength = this.plugin.getConfigManager().getMaxNameLength();
                           if (newName.length() < minLength || newName.length() > maxLength) {
                              this.plugin.getMessageManager().sendMessage(player, "name_too_long");
                           } else if (!newName.matches("^[a-zA-Z0-9_]+$")) {
                              this.plugin.getMessageManager().sendMessage(player, "invalid_team_name");
                           } else if (this.teamManager.getTeamByName(newName) != null) {
                              this.plugin.getMessageManager().sendMessage(player, "team_name_exists", Placeholder.unparsed("team", newName));
                           } else {
                              String oldName = team.getName();
                              team.setName(newName);
                              this.plugin.getStorageManager().getStorage().setTeamName(team.getId(), newName);
                              this.teamManager
                                 .publishCrossServerUpdate(team.getId(), "TEAM_UPDATED", player.getUniqueId().toString(), "name_change|" + newName);
                              this.plugin
                                 .getMessageManager()
                                 .sendMessage(player, "rename_success", Placeholder.unparsed("old_name", oldName), Placeholder.unparsed("new_name", newName));
                              new AdminTeamManageGUI(this.plugin, player, team, gui.getMemberPage()).open();
                           }
                        } else {
                           this.plugin.getMessageManager().sendMessage(player, "invalid_input");
                        }
                     }
                  );
               this.plugin.getMessageManager().sendRawMessage(player, "<yellow>Enter the new team name in chat:");
               break;
            case "edit-description":
               player.closeInventory();
               this.plugin.getChatInputManager().awaitInput(player, null, input -> {
                  if (input != null && !input.trim().isEmpty()) {
                     String newDesc = input.trim();
                     if (newDesc.length() > this.plugin.getConfigManager().getMaxDescriptionLength()) {
                        this.plugin.getMessageManager().sendMessage(player, "description_too_long");
                     } else {
                        team.setDescription(newDesc);
                        this.plugin.getStorageManager().getStorage().setTeamDescription(team.getId(), newDesc);
                        this.teamManager.publishCrossServerUpdate(team.getId(), "TEAM_UPDATED", player.getUniqueId().toString(), "description_change");
                        this.plugin.getMessageManager().sendMessage(player, "description_set");
                        new AdminTeamManageGUI(this.plugin, player, team, gui.getMemberPage()).open();
                     }
                  } else {
                     this.plugin.getMessageManager().sendMessage(player, "invalid_input");
                  }
               });
               this.plugin.getMessageManager().sendRawMessage(player, "<yellow>Enter the new description in chat:");
               break;
            case "edit-tag":
               player.closeInventory();
               this.plugin.getChatInputManager().awaitInput(player, null, input -> {
                  if (input != null && !input.trim().isEmpty()) {
                     String newTag = input.trim();
                     team.setTag(newTag);
                     this.plugin.getStorageManager().getStorage().setTeamTag(team.getId(), newTag);
                     this.teamManager.publishCrossServerUpdate(team.getId(), "TEAM_UPDATED", player.getUniqueId().toString(), "tag_change|" + newTag);
                     this.plugin.getMessageManager().sendMessage(player, "tag_set", Placeholder.unparsed("tag", newTag));
                     new AdminTeamManageGUI(this.plugin, player, team, gui.getMemberPage()).open();
                  } else {
                     this.plugin.getMessageManager().sendMessage(player, "invalid_input");
                  }
               });
               this.plugin.getMessageManager().sendRawMessage(player, "<yellow>Enter the new tag in chat:");
               break;
            case "toggle-public": {
               boolean newStatus = !team.isPublic();
               team.setPublic(newStatus);
               this.plugin.getStorageManager().getStorage().setPublicStatus(team.getId(), newStatus);
               this.teamManager.markTeamModified(team.getId());
               this.teamManager.publishCrossServerUpdate(team.getId(), "PUBLIC_STATUS_CHANGED", player.getUniqueId().toString(), String.valueOf(newStatus));
               this.plugin.getMessageManager().sendRawMessage(player, newStatus ? "<green>Team is now public" : "<red>Team is now private");
               new AdminTeamManageGUI(this.plugin, player, team, gui.getMemberPage()).open();
               break;
            }
            case "toggle-pvp": {
               boolean newStatus = !team.isPvpEnabled();
               team.setPvpEnabled(newStatus);
               this.plugin.getStorageManager().getStorage().setPvpStatus(team.getId(), newStatus);
               this.teamManager.markTeamModified(team.getId());
               this.teamManager.publishCrossServerUpdate(team.getId(), "PVP_STATUS_CHANGED", player.getUniqueId().toString(), String.valueOf(newStatus));
               this.plugin.getMessageManager().sendRawMessage(player, newStatus ? "<green>PvP enabled" : "<red>PvP disabled");
               new AdminTeamManageGUI(this.plugin, player, team, gui.getMemberPage()).open();
               break;
            }
            case "edit-balance":
               player.closeInventory();
               this.plugin
                  .getChatInputManager()
                  .awaitInput(
                     player,
                     null,
                     input -> {
                        if (input != null && !input.trim().isEmpty()) {
                           try {
                              double newBalance = Double.parseDouble(input.trim());
                              if (newBalance < 0.0) {
                                 this.plugin.getMessageManager().sendRawMessage(player, "<red>Balance cannot be negative");
                                 return;
                              }

                              double oldBalance = team.getBalance();
                              team.setBalance(newBalance);
                              this.plugin.getStorageManager().getStorage().updateTeamBalance(team.getId(), newBalance);
                              this.teamManager
                                 .publishCrossServerUpdate(team.getId(), "ADMIN_BALANCE_SET", player.getUniqueId().toString(), String.valueOf(newBalance));
                              this.plugin.getMessageManager().sendRawMessage(player, "<green>Balance set to <white>" + String.format("%.2f", newBalance));
                              new AdminTeamManageGUI(this.plugin, player, team, gui.getMemberPage()).open();
                           } catch (NumberFormatException e) {
                              this.plugin.getMessageManager().sendRawMessage(player, "<red>Invalid number format");
                           }
                        } else {
                           this.plugin.getMessageManager().sendMessage(player, "invalid_input");
                        }
                     }
                  );
               this.plugin.getMessageManager().sendRawMessage(player, "<yellow>Enter the new balance:");
               break;
            case "edit-stats":
               player.closeInventory();
               this.plugin.getChatInputManager().awaitInput(player, null, input -> {
                  if (input != null && !input.trim().isEmpty()) {
                     String[] parts = input.trim().split(" ");
                     if (parts.length != 2) {
                        this.plugin.getMessageManager().sendRawMessage(player, "<red>Usage: <kills> <deaths>");
                     } else {
                        try {
                           int kills = Integer.parseInt(parts[0]);
                           int deaths = Integer.parseInt(parts[1]);
                           if (kills < 0 || deaths < 0) {
                              this.plugin.getMessageManager().sendRawMessage(player, "<red>Stats cannot be negative");
                              return;
                           }

                           team.setKills(kills);
                           team.setDeaths(deaths);
                           this.plugin.getStorageManager().getStorage().updateTeamStats(team.getId(), kills, deaths);
                           this.teamManager.publishCrossServerUpdate(team.getId(), "ADMIN_STATS_SET", player.getUniqueId().toString(), kills + ":" + deaths);
                           this.plugin.getMessageManager().sendRawMessage(player, "<green>Stats updated: <white>" + kills + " kills, " + deaths + " deaths");
                           new AdminTeamManageGUI(this.plugin, player, team, gui.getMemberPage()).open();
                        } catch (NumberFormatException e) {
                           this.plugin.getMessageManager().sendRawMessage(player, "<red>Invalid number format");
                        }
                     }
                  } else {
                     this.plugin.getMessageManager().sendMessage(player, "invalid_input");
                  }
               });
               this.plugin.getMessageManager().sendRawMessage(player, "<yellow>Enter kills and deaths (e.g., '100 50'):");
               break;
            case "teleport-home":
               player.closeInventory();
               this.teamManager.adminTeleportTeamHome(player, team.getName());
               break;
            case "view-enderchest":
               player.closeInventory();
               this.teamManager.adminOpenEnderChest(player, team.getName());
               break;
            case "next-members":
               new AdminTeamManageGUI(this.plugin, player, team, gui.getMemberPage() + 1).open();
               break;
            case "prev-members":
               new AdminTeamManageGUI(this.plugin, player, team, gui.getMemberPage() - 1).open();
               break;
            case "refresh":
               new AdminTeamManageGUI(this.plugin, player, team, gui.getMemberPage()).open();
               break;
            default:
               if (action.startsWith("member-")) {
                  String uuidStr = action.substring(7);

                  try {
                     UUID memberUuid = UUID.fromString(uuidStr);
                     TeamPlayer member = team.getMember(memberUuid);
                     if (member == null) {
                        this.plugin.getMessageManager().sendRawMessage(player, "<red>Member not found!");
                        return;
                     }

                     if (memberUuid.equals(team.getOwnerUuid())) {
                        this.plugin.getMessageManager().sendRawMessage(player, "<red>Cannot edit the team owner!");
                        return;
                     }

                     new AdminMemberEditGUI(this.plugin, player, team, member).open();
                  } catch (IllegalArgumentException e) {
                     this.plugin.getLogger().warning("Invalid UUID in admin member click: " + uuidStr);
                  }
               }
         }
      }
   }

   private void onAdminMemberEditGUIClick(Player player, AdminMemberEditGUI gui, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         Team team = gui.getTeam();
         TeamPlayer member = gui.getMember();
         switch (action) {
            case "back-button":
               new AdminTeamManageGUI(this.plugin, player, team, 0).open();
               break;
            case "kick-member":
               this.plugin
                  .getTaskRunner()
                  .runAsync(
                     () -> {
                        this.plugin.getStorageManager().getStorage().removeMemberFromTeam(member.getPlayerUuid());
                        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(member.getPlayerUuid());
                        String targetName = targetPlayer.getName() != null ? targetPlayer.getName() : "Unknown";
                        this.teamManager
                           .publishCrossServerUpdate(team.getId(), "ADMIN_MEMBER_KICK", player.getUniqueId().toString(), member.getPlayerUuid().toString());
                        this.plugin.getTaskRunner().run(() -> {
                           team.removeMember(member.getPlayerUuid());
                           this.plugin.getMessageManager().sendRawMessage(player, "<green>Kicked <white>" + targetName + " <green>from the team");
                           new AdminTeamManageGUI(this.plugin, player, team, 0).open();
                        });
                     }
                  );
               this.plugin.getTaskRunner().runTaskLater(() -> new AdminTeamManageGUI(this.plugin, player, team, 0).open(), 10L);
               break;
            case "toggle-withdraw":
               boolean newValue = !member.canWithdraw();
               member.setCanWithdraw(newValue);

               try {
                  this.plugin.getStorageManager().getStorage().updateMemberPermission(team.getId(), member.getPlayerUuid(), "withdraw", newValue);
               } catch (Exception e) {
                  this.plugin.getLogger().severe("Failed to update withdraw permission: " + e.getMessage());
               }

               this.teamManager
                  .publishCrossServerUpdate(
                     team.getId(), "ADMIN_PERMISSION_UPDATE", player.getUniqueId().toString(), member.getPlayerUuid() + ":withdraw:" + newValue
                  );
               this.plugin.getMessageManager().sendRawMessage(player, (newValue ? "<green>Enabled" : "<red>Disabled") + " <white>withdraw permission");
               new AdminMemberEditGUI(this.plugin, player, team, member).open();
               break;
            case "toggle-enderchest":
               boolean ecNewValue = !member.canUseEnderChest();
               member.setCanUseEnderChest(ecNewValue);

               try {
                  this.plugin.getStorageManager().getStorage().updateMemberPermission(team.getId(), member.getPlayerUuid(), "enderchest", ecNewValue);
               } catch (Exception e) {
                  this.plugin.getLogger().severe("Failed to update enderchest permission: " + e.getMessage());
               }

               this.teamManager
                  .publishCrossServerUpdate(
                     team.getId(), "ADMIN_PERMISSION_UPDATE", player.getUniqueId().toString(), member.getPlayerUuid() + ":enderchest:" + ecNewValue
                  );
               this.plugin.getMessageManager().sendRawMessage(player, (ecNewValue ? "<green>Enabled" : "<red>Disabled") + " <white>enderchest permission");
               new AdminMemberEditGUI(this.plugin, player, team, member).open();
               break;
            case "toggle-sethome":
               boolean shNewValue = !member.canSetHome();
               member.setCanSetHome(shNewValue);

               try {
                  this.plugin.getStorageManager().getStorage().updateMemberPermission(team.getId(), member.getPlayerUuid(), "sethome", shNewValue);
               } catch (Exception e) {
                  this.plugin.getLogger().severe("Failed to update sethome permission: " + e.getMessage());
               }

               this.teamManager
                  .publishCrossServerUpdate(
                     team.getId(), "ADMIN_PERMISSION_UPDATE", player.getUniqueId().toString(), member.getPlayerUuid() + ":sethome:" + shNewValue
                  );
               this.plugin.getMessageManager().sendRawMessage(player, (shNewValue ? "<green>Enabled" : "<red>Disabled") + " <white>set home permission");
               new AdminMemberEditGUI(this.plugin, player, team, member).open();
               break;
            case "toggle-usehome":
               boolean uhNewValue = !member.canUseHome();
               member.setCanUseHome(uhNewValue);

               try {
                  this.plugin.getStorageManager().getStorage().updateMemberPermission(team.getId(), member.getPlayerUuid(), "usehome", uhNewValue);
               } catch (Exception e) {
                  this.plugin.getLogger().severe("Failed to update usehome permission: " + e.getMessage());
               }

               this.teamManager
                  .publishCrossServerUpdate(
                     team.getId(), "ADMIN_PERMISSION_UPDATE", player.getUniqueId().toString(), member.getPlayerUuid() + ":usehome:" + uhNewValue
                  );
               this.plugin.getMessageManager().sendRawMessage(player, (uhNewValue ? "<green>Enabled" : "<red>Disabled") + " <white>use home permission");
               new AdminMemberEditGUI(this.plugin, player, team, member).open();
               break;
            case "toggle-promote-co-owner":
               if (!team.isOwner(player.getUniqueId()) && !player.hasPermission("justteams.admin")) {
                  this.plugin.getMessageManager().sendMessage(player, "not_owner");
                  EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                  return;
               }

               if (member.getRole() != TeamRole.CO_OWNER) {
                  this.plugin.getMessageManager().sendRawMessage(player, "<red>This permission can only be granted to Co-owners.");
                  EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                  return;
               }

               boolean pcAdminNewValue = !member.canPromoteToCoOwner();
               member.setCanPromoteToCoOwner(pcAdminNewValue);

               try {
                  this.plugin.getStorageManager().getStorage().updateMemberPermission(team.getId(), member.getPlayerUuid(), "promote_co_owner", pcAdminNewValue);
               } catch (Exception e) {
                  this.plugin.getLogger().severe("Failed to update promote-co-owner permission: " + e.getMessage());
               }

               this.teamManager
                  .publishCrossServerUpdate(
                     team.getId(), "ADMIN_PERMISSION_UPDATE", player.getUniqueId().toString(), member.getPlayerUuid() + ":promote_co_owner:" + pcAdminNewValue
                  );
               this.plugin
                  .getMessageManager()
                  .sendRawMessage(player, (pcAdminNewValue ? "<green>Enabled" : "<red>Disabled") + " <white>promote-to-co-owner permission");
               new AdminMemberEditGUI(this.plugin, player, team, member).open();
               break;
            case "promote-member":
               if (member.getRole() == TeamRole.OWNER) {
                  this.plugin.getMessageManager().sendRawMessage(player, "<red>Member is already the owner!");
                  return;
               }

               if (member.getRole() == TeamRole.CO_OWNER) {
                  this.plugin.getMessageManager().sendRawMessage(player, "<red>Member is already a co-owner!");
                  return;
               }

               member.setRole(TeamRole.CO_OWNER);
               this.plugin.getStorageManager().getStorage().updateMemberRole(team.getId(), member.getPlayerUuid(), TeamRole.CO_OWNER);
               this.teamManager
                  .publishCrossServerUpdate(team.getId(), "ADMIN_MEMBER_PROMOTE", player.getUniqueId().toString(), member.getPlayerUuid().toString());
               this.plugin.getMessageManager().sendRawMessage(player, "<green>Promoted member to Co-Owner");
               new AdminMemberEditGUI(this.plugin, player, team, member).open();
               break;
            case "demote-member":
               if (member.getRole() == TeamRole.MEMBER) {
                  this.plugin.getMessageManager().sendRawMessage(player, "<red>Member is already at the lowest rank!");
                  return;
               }

               if (member.getRole() == TeamRole.OWNER) {
                  this.plugin.getMessageManager().sendRawMessage(player, "<red>Cannot demote the owner!");
                  return;
               }

               member.setRole(TeamRole.MEMBER);
               this.plugin.getStorageManager().getStorage().updateMemberRole(team.getId(), member.getPlayerUuid(), TeamRole.MEMBER);
               this.teamManager
                  .publishCrossServerUpdate(team.getId(), "ADMIN_MEMBER_DEMOTE", player.getUniqueId().toString(), member.getPlayerUuid().toString());
               this.plugin.getMessageManager().sendRawMessage(player, "<green>Demoted member to regular Member");
               new AdminMemberEditGUI(this.plugin, player, team, member).open();
         }
      }
   }

   private void onConfirmGUIClick(ConfirmGUI gui, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         if (action.equals("confirm")) {
            gui.handleConfirm();
         } else if (action.equals("cancel")) {
            gui.handleCancel();
         }
      }
   }

   private void onWarpsGUIClick(Player player, WarpsGUI gui, ClickType click, ItemStack clickedItem, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         if (action.equals("back-button")) {
            new TeamGUI(this.plugin, gui.getTeam(), player).open();
         } else if (action.equals("warp_item")) {
            String warpName = (String)pdc.get(new NamespacedKey(JustTeams.getInstance(), "warp_name"), PersistentDataType.STRING);
            if (warpName != null) {
               if (click.isLeftClick()) {
                  this.plugin.getTeamManager().teleportToTeamWarp(player, warpName, null);
                  player.closeInventory();
               } else if (click.isRightClick()) {
                  this.plugin.getTeamManager().deleteTeamWarp(player, warpName);
                  gui.initializeItems();
               }
            }
         }
      }
   }

   private void onBlacklistGUIClick(Player player, BlacklistGUI gui, ClickType click, ItemStack clickedItem, PersistentDataContainer pdc) {
      if (this.plugin.getConfigManager().isDebugEnabled()) {
         this.plugin.getDebugLogger().log("=== BLACKLIST GUI CLICK DEBUG ===");
         this.plugin.getDebugLogger().log("Player: " + player.getName());
         this.plugin.getDebugLogger().log("Click type: " + click);
         this.plugin.getDebugLogger().log("Clicked item: " + (clickedItem != null ? clickedItem.getType() : "null"));
         this.plugin.getDebugLogger().log("PDC has action key: " + pdc.has(this.actionKey, PersistentDataType.STRING));
      }

      if (!pdc.has(this.actionKey, PersistentDataType.STRING)) {
         this.plugin.getLogger().warning("No action key found in PDC for blacklist click");
      } else {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         this.plugin.getLogger().info("Action retrieved: " + action);
         if (action.equals("back-button")) {
            this.plugin.getLogger().info("Back button clicked, opening team GUI");
            new TeamGUI(this.plugin, gui.getTeam(), player).open();
         } else if (action.startsWith("remove-blacklist:")) {
            this.plugin.getLogger().info("Remove blacklist action detected: " + action);
            if (!this.checkActionCooldown(player, "remove-blacklist", 2000L)) {
               this.plugin.getLogger().info("Rate limit hit for blacklist removal by " + player.getName());
               return;
            }

            String uuidString = action.substring("remove-blacklist:".length());
            this.plugin.getLogger().info("UUID string extracted: " + uuidString);

            UUID targetUuid;
            try {
               targetUuid = UUID.fromString(uuidString);
               this.plugin.getLogger().info("UUID parsed successfully: " + targetUuid);
               this.plugin.getLogger().info("Team ID: " + gui.getTeam().getId());
            } catch (IllegalArgumentException e) {
               this.plugin.getLogger().warning("Invalid UUID format in blacklist removal action: " + uuidString);
               return;
            }

            BlacklistGUI finalGui = gui;
            UUID finalTargetUuid = targetUuid;
            this.plugin.getLogger().info("Starting async blacklist removal...");
            this.plugin
               .getTaskRunner()
               .runAsync(
                  () -> {
                     try {
                        this.plugin.getLogger().info("Executing blacklist removal in async thread for " + finalTargetUuid);
                        this.plugin.getLogger().info("Storage manager: " + this.plugin.getStorageManager());
                        this.plugin.getLogger().info("Storage: " + this.plugin.getStorageManager().getStorage());
                        boolean success = this.plugin.getStorageManager().getStorage().removePlayerFromBlacklist(finalGui.getTeam().getId(), finalTargetUuid);
                        this.plugin.getLogger().info("Blacklist removal result: " + success + " for " + finalTargetUuid);
                        if (success) {
                           this.plugin.getLogger().info("Blacklist removal successful, refreshing GUI...");
                           this.plugin
                              .getTaskRunner()
                              .runOnEntity(
                                 player,
                                 () -> {
                                    try {
                                       this.plugin
                                          .getMessageManager()
                                          .sendMessage(
                                             player,
                                             "player_removed_from_blacklist",
                                             Placeholder.unparsed("target", Bukkit.getOfflinePlayer(finalTargetUuid).getName())
                                          );
                                       this.plugin.getLogger().info("Success message sent, now refreshing GUI for " + player.getName());
                                       finalGui.refresh();
                                       this.plugin.getLogger().info("GUI refresh called successfully");
                                    } catch (Exception e) {
                                       this.plugin.getLogger().severe("Error in sync thread for blacklist removal: " + e.getMessage());
                                       this.plugin.getLogger().severe("Error in GUI action: " + e.getMessage());
                                    }
                                 }
                              );
                        } else {
                           this.plugin.getLogger().warning("Blacklist removal failed in database");
                           this.plugin
                              .getTaskRunner()
                              .runOnEntity(player, () -> this.plugin.getMessageManager().sendMessage(player, "remove_blacklist_failed"));
                        }
                     } catch (Exception e) {
                        this.plugin.getLogger().severe("Error removing player from blacklist: " + e.getMessage());
                        this.plugin.getLogger().severe("Error in GUI action: " + e.getMessage());
                        this.plugin.getTaskRunner().runOnEntity(player, () -> this.plugin.getMessageManager().sendMessage(player, "remove_blacklist_failed"));
                     }
                  }
               );
         } else {
            this.plugin.getLogger().warning("Unknown action in blacklist GUI: " + action);
         }

         if (this.plugin.getConfigManager().isDebugEnabled()) {
            this.plugin.getDebugLogger().log("=== END BLACKLIST GUI CLICK DEBUG ===");
         }
      }
   }

   @EventHandler
   public void onInventoryDrag(InventoryDragEvent event) {
      if (event.getWhoClicked() instanceof Player) {
         InventoryHolder holder = event.getView().getTopInventory().getHolder();
         boolean isOurGui = holder instanceof IRefreshableGUI
            || holder instanceof NoTeamGUI
            || holder instanceof ConfirmGUI
            || holder instanceof AdminGUI
            || holder instanceof AdminTeamListGUI
            || holder instanceof AdminTeamManageGUI
            || holder instanceof TeamSettingsGUI
            || holder instanceof LeaderboardCategoryGUI
            || holder instanceof LeaderboardViewGUI
            || holder instanceof JoinRequestGUI
            || holder instanceof InvitesGUI
            || holder instanceof WarpsGUI
            || holder instanceof BlacklistGUI;
         if (isOurGui) {
            event.setCancelled(true);
         }
      }
   }

   private void onAllyGUIClick(Player player, AllyGUI gui, PersistentDataContainer pdc) {
      if (pdc.has(this.actionKey, PersistentDataType.STRING)) {
         String action = (String)pdc.get(this.actionKey, PersistentDataType.STRING);
         if ("back-button".equals(action)) {
            player.closeInventory();
            this.plugin.getTaskRunner().runOnEntity(player, () -> new TeamGUI(this.plugin, gui.getTeam(), player).open());
         } else if (action.startsWith("remove-ally:")) {
            String[] parts = action.split(":");
            if (parts.length >= 2) {
               try {
                  int allyTeamId = Integer.parseInt(parts[1]);
                  player.closeInventory();
                  this.plugin.getTaskRunner().runAsync(() -> {
                     Optional<Team> allyTeamOpt = this.plugin.getStorageManager().getStorage().findTeamById(allyTeamId);
                     if (allyTeamOpt.isPresent()) {
                        this.plugin.getTaskRunner().runOnEntity(player, () -> {
                           this.plugin.getTeamManager().removeAlly(player, allyTeamOpt.get().getName());
                           this.plugin.getTaskRunner().runTaskLater(() -> {
                              if (player.isOnline()) {
                                 new AllyGUI(this.plugin, gui.getTeam(), player).open();
                              }
                           }, 5L);
                        });
                     }
                  });
               } catch (NumberFormatException e) {
                  this.plugin.getLogger().warning("Invalid ally team ID in action: " + action);
               }
            }
         } else {
            if (action.startsWith("ally-request:")) {
               String[] parts = action.split(":");
               if (parts.length < 2) {
                  return;
               }

               try {
                  int senderTeamId = Integer.parseInt(parts[1]);
                  player.closeInventory();
                  new ConfirmGUI(this.plugin, player, this.plugin.getMiniMessage().deserialize("<yellow>Accept ally request?"), confirmed -> {
                     if (confirmed) {
                        this.plugin.getTeamManager().acceptAllyRequest(player, senderTeamId);
                     } else {
                        this.plugin.getTeamManager().denyAllyRequest(player, senderTeamId);
                     }

                     this.plugin.getTaskRunner().runTaskLater(() -> {
                        if (player.isOnline()) {
                           Team team = this.plugin.getTeamManager().getPlayerTeam(player.getUniqueId());
                           if (team != null) {
                              new AllyGUI(this.plugin, team, player).open();
                           }
                        }
                     }, 10L);
                  }).open();
               } catch (NumberFormatException e) {
                  this.plugin.getLogger().warning("Invalid sender team ID in action: " + action);
               }
            }
         }
      }
   }
}
