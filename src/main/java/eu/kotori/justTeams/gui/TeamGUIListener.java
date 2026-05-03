package eu.kotori.justTeams.gui;
import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.gui.admin.AdminGUI;
import eu.kotori.justTeams.gui.admin.AdminTeamListGUI;
import eu.kotori.justTeams.gui.admin.AdminTeamManageGUI;
import eu.kotori.justTeams.gui.admin.AdminMemberEditGUI;
import eu.kotori.justTeams.gui.sub.TeamSettingsGUI;
import eu.kotori.justTeams.gui.BlacklistGUI;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamManager;
import eu.kotori.justTeams.team.TeamPlayer;
import eu.kotori.justTeams.team.TeamRole;
import eu.kotori.justTeams.util.EffectsUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private boolean checkActionCooldown(Player player, String action, long cooldownMs) {
        if (player == null || action == null) {
            return false;
        }
        String key = player.getUniqueId() + ":" + action;
        long currentTime = System.currentTimeMillis();
        synchronized (actionLock) {
            return actionCooldowns.compute(key, (k, lastActionTime) -> {
                if (lastActionTime == null || (currentTime - lastActionTime) >= cooldownMs) {
                    return currentTime;
                }
                return lastActionTime;
            }) == currentTime;
        }
    }
    @EventHandler
    public void onGUIClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        try {
            InventoryHolder holder = event.getView().getTopInventory().getHolder();
            boolean isOurGui = holder instanceof IRefreshableGUI || holder instanceof NoTeamGUI || holder instanceof ConfirmGUI ||
                    holder instanceof AdminGUI || holder instanceof AdminTeamListGUI || holder instanceof AdminTeamManageGUI ||
                    holder instanceof AdminMemberEditGUI || holder instanceof TeamSettingsGUI || holder instanceof LeaderboardCategoryGUI || 
                    holder instanceof LeaderboardViewGUI || holder instanceof JoinRequestGUI || holder instanceof InvitesGUI || 
                    holder instanceof WarpsGUI || holder instanceof BlacklistGUI;
            if (!isOurGui) {
                return;
            }
            event.setCancelled(true);
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
                return;
            }
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() == Material.AIR) return;
            ItemMeta meta = clickedItem.getItemMeta();
            if (meta == null) return;
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (holder instanceof BlacklistGUI) {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getDebugLogger().log("=== BLACKLIST GUI MAIN CLICK DEBUG ===");
                    plugin.getDebugLogger().log("Player: " + player.getName());
                    plugin.getDebugLogger().log("Clicked item type: " + clickedItem.getType());
                    plugin.getDebugLogger().log("Clicked item has meta: " + (meta != null));
                    plugin.getDebugLogger().log("PDC has action key: " + pdc.has(actionKey, PersistentDataType.STRING));
                    if (pdc.has(actionKey, PersistentDataType.STRING)) {
                        String action = pdc.get(actionKey, PersistentDataType.STRING);
                        plugin.getDebugLogger().log("Action found: " + action);
                    } else {
                        plugin.getLogger().warning("No action key found in blacklist item!");
                        for (NamespacedKey key : pdc.getKeys()) {
                            plugin.getDebugLogger().log("PDC key found: " + key.toString());
                        }
                    }
                    plugin.getDebugLogger().log("=== END BLACKLIST GUI MAIN CLICK DEBUG ===");
                }
            }
            if (!pdc.has(actionKey, PersistentDataType.STRING)) {
                plugin.getDebugLogger().log("GUI click without valid action key from " + player.getName());
                return;
            }
            String action = pdc.get(actionKey, PersistentDataType.STRING);
            if (action == null || action.isEmpty() || action.length() > 50) {
                plugin.getDebugLogger().log("Invalid action in GUI click from " + player.getName() + ": " + action);
                return;
            }
            if ("back-button".equals(action)) {
                plugin.getDebugLogger().log("Back button clicked by " + player.getName() + " in " + holder.getClass().getSimpleName());
            }
        if (holder instanceof TeamGUI gui) onTeamGUIClick(player, gui, clickedItem, pdc);
        else if (holder instanceof MemberEditGUI gui) onMemberEditGUIClick(player, gui, pdc);
        else if (holder instanceof BankGUI gui) onBankGUIClick(player, gui, pdc);
        else if (holder instanceof TeamSettingsGUI gui) onTeamSettingsGUIClick(player, gui, pdc);
        else if (holder instanceof JoinRequestGUI gui) onJoinRequestGUIClick(player, gui, event.getClick(), clickedItem);
        else if (holder instanceof InvitesGUI gui) onInvitesGUIClick(player, gui, event.getClick(), clickedItem);
        else if (holder instanceof LeaderboardCategoryGUI) onLeaderboardCategoryGUIClick(player, pdc);
        else if (holder instanceof LeaderboardViewGUI) onLeaderboardViewGUIClick(player, pdc);
        else if (holder instanceof NoTeamGUI) onNoTeamGUIClick(player, pdc);
        else if (holder instanceof AdminGUI) onAdminGUIClick(player, pdc);
        else if (holder instanceof AdminTeamListGUI gui) onAdminTeamListGUIClick(player, gui, clickedItem, pdc);
        else if (holder instanceof AdminTeamManageGUI gui) onAdminTeamManageGUIClick(player, gui, pdc);
        else if (holder instanceof AdminMemberEditGUI gui) onAdminMemberEditGUIClick(player, gui, pdc);
        else if (holder instanceof ConfirmGUI gui) onConfirmGUIClick(gui, pdc);
        else if (holder instanceof WarpsGUI) onWarpsGUIClick(player, (WarpsGUI) holder, event.getClick(), clickedItem, pdc);
        else if (holder instanceof BlacklistGUI gui) onBlacklistGUIClick(player, gui, event.getClick(), clickedItem, pdc);
        } catch (Exception e) {
            plugin.getLogger().severe("Error handling GUI click for " + player.getName() + ": " + e.getMessage());
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().severe("Error in GUI action: " + e.getMessage());
            }
            plugin.getMessageManager().sendMessage(player, "gui_error");
            event.setCancelled(true);
        }
    }
    private void onTeamGUIClick(Player player, TeamGUI gui, ItemStack clickedItem, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        Team team = gui.getTeam();
        if (team == null) {
            plugin.getDebugLogger().log("TeamGUI click with null team for " + player.getName());
            return;
        }
        if (!team.isMember(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            player.closeInventory();
            return;
        }
        switch (action) {
            case "player-head" -> {
                if (clickedItem.getItemMeta() instanceof SkullMeta skullMeta && skullMeta.getOwningPlayer() != null) {
                    UUID targetUuid = skullMeta.getOwningPlayer().getUniqueId();
                    if (targetUuid != null) {
                        if (targetUuid.equals(player.getUniqueId())) {
                            plugin.getMessageManager().sendMessage(player, "cannot_edit_own_permissions");
                            return;
                        }
                        TeamPlayer viewerMember = team.getMember(player.getUniqueId());
                        TeamPlayer targetMember = team.getMember(targetUuid);
                        if (viewerMember == null || targetMember == null) {
                            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
                            return;
                        }
                        boolean canEdit = false;
                        if (viewerMember.getRole() == TeamRole.OWNER) {
                            canEdit = true;
                        } else if (viewerMember.getRole() == TeamRole.CO_OWNER) {
                            canEdit = targetMember.getRole() == TeamRole.MEMBER;
                        }
                        if (canEdit) {
                            new MemberEditGUI(plugin, team, player, targetUuid).open();
                        } else {
                            plugin.getMessageManager().sendMessage(player, "no_permission");
                        }
                    }
                }
            }
            case "join-requests" -> {
                new JoinRequestGUI(plugin, player, team).open();
            }
            case "join-requests-locked" -> {
                plugin.getMessageManager().sendMessage(player, "join_requests_permission_denied");
            }
            case "warps" -> {
                if (!plugin.getFeatureRestrictionManager().canAffordAndPay(player, "warp")) {
                    return;
                }
                try {
                    Class.forName("eu.kotori.justTeams.gui.WarpsGUI");
                    teamManager.openWarpsGUI(player);
                } catch (ClassNotFoundException e) {
                    teamManager.listTeamWarps(player);
                }
            }
            case "bank" -> {
                TeamPlayer viewerMember = team.getMember(player.getUniqueId());
                if (viewerMember == null || (!viewerMember.canWithdraw() && !player.hasPermission("justteams.bypass.bank.use"))) {
                    plugin.getMessageManager().sendMessage(player, "no_permission");
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                    return;
                }
                new BankGUI(plugin, player, team).open();
            }
            case "bank-locked" -> {
                plugin.getMessageManager().sendMessage(player, "bank_permission_denied");
            }
            case "home" -> {
                TeamPlayer viewerMember = team.getMember(player.getUniqueId());
                if (viewerMember == null || (!viewerMember.canUseHome() && !player.hasPermission("justteams.bypass.home.use"))) {
                    plugin.getMessageManager().sendMessage(player, "no_permission");
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                    return;
                }
                if (!checkActionCooldown(player, "home", 5000)) {
                    return;
                }
                teamManager.teleportToHome(player);
            }
            case "ender-chest" -> {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().info("Enderchest clicked by " + player.getName() + " in team " + team.getName());
                }
                TeamPlayer viewerMember = team.getMember(player.getUniqueId());
                if (viewerMember == null) {
                    plugin.getLogger().warning("Player " + player.getName() + " not found in team " + team.getName());
                    plugin.getMessageManager().sendMessage(player, "player_not_in_team");
                    return;
                }
                boolean hasPermission = viewerMember.canUseEnderChest();
                boolean hasBypass = player.hasPermission("justteams.bypass.enderchest.use");
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().info("Enderchest permission check for " + player.getName() +
                        " - canUseEnderChest: " + hasPermission +
                        ", hasBypass: " + hasBypass +
                        ", member: " + viewerMember.getPlayerUuid() +
                        ", team: " + team.getName() +
                        ", teamId: " + team.getId());
                }
                if (!hasPermission && !hasBypass) {
                    plugin.getLogger().warning("Player " + player.getName() + " attempted to access enderchest without permission!");
                    plugin.getLogger().warning("Permission details - canUseEnderChest: " + hasPermission + ", hasBypass: " + hasBypass);
                    plugin.getMessageManager().sendMessage(player, "no_permission");
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                    return;
                }
                if (!checkActionCooldown(player, "enderchest", 2000)) {
                    if (plugin.getConfigManager().isDebugEnabled()) {
                        plugin.getLogger().info("Enderchest action blocked by cooldown for " + player.getName());
                    }
                    return;
                }
                
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().info("Opening enderchest for " + player.getName());
                }
                teamManager.openEnderChest(player);
            }
            case "ender-chest-locked" -> {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().info("Enderchest-locked clicked by " + player.getName() + " in team " + team.getName());
                }
                plugin.getMessageManager().sendMessage(player, "enderchest_permission_denied");
                EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            }
            case "sort" -> {
                team.cycleSortType();
                new TeamGUI(plugin, team, player).open();
            }
            case "pvp-toggle" -> {
                if (!checkActionCooldown(player, "pvp-toggle", 2000)) {
                    return;
                }
                teamManager.togglePvpStatus(player);
                gui.initializeItems();
            }
            case "team-settings" -> {
                new TeamSettingsGUI(plugin, player, team).open();
            }
            case "settings" -> {
                new TeamSettingsGUI(plugin, player, team).open();
            }
            case "settings-locked" -> {
                plugin.getMessageManager().sendMessage(player, "settings_permission_denied");
            }
            case "disband-button" -> {
                if (!checkActionCooldown(player, "disband", 10000)) {
                    return;
                }
                new ConfirmGUI(plugin, player, "Are you sure you want to disband your team? This cannot be undone.", confirmed -> {
                    if (confirmed) {
                        teamManager.disbandTeam(player);
                    }
                }).open();
            }
            case "leave-button" -> {
                if (!checkActionCooldown(player, "leave", 5000)) {
                    return;
                }
                new ConfirmGUI(plugin, player, "Are you sure you want to leave the team?", confirmed -> {
                    if (confirmed) {
                        teamManager.leaveTeam(player);
                    }
                }).open();
            }
            case "blacklist" -> {
                new BlacklistGUI(plugin, team, player).open();
            }
            default -> {
                plugin.getDebugLogger().log("Unknown TeamGUI action: " + action + " from " + player.getName());
            }
        }
    }
    private void onMemberEditGUIClick(Player player, MemberEditGUI gui, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        TeamPlayer targetMember = gui.getTargetMember();
        if (targetMember == null) {
            player.closeInventory();
            return;
        }
        switch (action) {
            case "promote-button" -> {
                if (!checkActionCooldown(player, "promote", 2000)) {
                    return;
                }
                teamManager.promotePlayer(player, gui.getTargetUuid());
            }
            case "demote-button" -> {
                if (!checkActionCooldown(player, "demote", 2000)) {
                    return;
                }
                teamManager.demotePlayer(player, gui.getTargetUuid());
            }
            case "kick-button" -> {
                if (!checkActionCooldown(player, "kick", 2000)) {
                    return;
                }
                teamManager.kickPlayer(player, gui.getTargetUuid());
            }
            case "transfer-button" -> {
                if (!checkActionCooldown(player, "transfer", 5000)) {
                    return;
                }
                teamManager.transferOwnership(player, gui.getTargetUuid());
            }
            case "back-button" -> {
                new TeamGUI(plugin, gui.getTeam(), player).open();
            }
            case "withdraw-permission", "enderchest-permission", "sethome-permission", "usehome-permission" -> {
                if (!checkActionCooldown(player, "permission-change", 1000)) {
                    return;
                }
                boolean isSelfView = gui.getTargetUuid().equals(player.getUniqueId());
                if (isSelfView) {
                    plugin.getMessageManager().sendMessage(player, "cannot_edit_own_permissions");
                    return;
                }
                boolean canWithdraw = targetMember.canWithdraw();
                boolean canUseEC = targetMember.canUseEnderChest();
                boolean canSetHome = targetMember.canSetHome();
                boolean canUseHome = targetMember.canUseHome();
                switch (action) {
                    case "withdraw-permission" -> canWithdraw = !canWithdraw;
                    case "enderchest-permission" -> canUseEC = !canUseEC;
                    case "sethome-permission" -> canSetHome = !canSetHome;
                    case "usehome-permission" -> canUseHome = !canUseHome;
                }
                teamManager.updateMemberPermissions(player, targetMember.getPlayerUuid(), canWithdraw, canUseEC, canSetHome, canUseHome);
            }
            case "withdraw-permission-view", "enderchest-permission-view", "sethome-permission-view", "usehome-permission-view" -> {
                plugin.getMessageManager().sendMessage(player, "view_only_mode");
            }
            default -> { return; }
        }
        gui.initializeItems();
    }
    private void onBankGUIClick(Player player, BankGUI gui, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        switch (action) {
            case "back-button" -> {
                new TeamGUI(plugin, gui.getTeam(), player).open();
            }
            case "withdraw-locked" -> {
                plugin.getMessageManager().sendMessage(player, "gui_action_locked");
                EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            }
            case "deposit", "withdraw" -> {
                if (!checkActionCooldown(player, "bank-action", 1000)) {
                    return;
                }
                player.closeInventory();
                boolean isDeposit = action.equals("deposit");
                String promptAction = isDeposit ? "deposit" : "withdraw";
                String prompt = plugin.getMessageManager().getRawMessage("prompt_bank_amount").replace("<action>", promptAction);
                plugin.getMessageManager().sendRawMessage(player, prompt);
                plugin.getChatInputManager().awaitInput(player, gui, input -> {
                    try {
                        double amount = Double.parseDouble(input);
                        if (amount <= 0) {
                            plugin.getMessageManager().sendMessage(player, "bank_invalid_amount");
                        } else if (amount > 1_000_000_000) {
                            plugin.getMessageManager().sendMessage(player, "bank_amount_too_large");
                        } else {
                            if (isDeposit) {
                                teamManager.deposit(player, amount);
                            } else {
                                teamManager.withdraw(player, amount);
                            }
                        }
                    } catch (NumberFormatException e) {
                        plugin.getMessageManager().sendMessage(player, "bank_invalid_amount");
                    }
                    plugin.getTaskRunner().runOnEntity(player, gui::open);
                });
            }
        }
    }
    private void onTeamSettingsGUIClick(Player player, TeamSettingsGUI gui, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        switch (action) {
            case "back-button" -> {
                new TeamGUI(plugin, gui.getTeam(), player).open();
            }
            case "toggle-public" -> {
                if (!checkActionCooldown(player, "toggle-public", 2000)) {
                    return;
                }
                plugin.getTeamManager().togglePublicStatus(player);
                gui.initializeItems();
            }
            case "change-tag", "change-description" -> {
                boolean isTag = action.equals("change-tag");
                
                if (isTag && !plugin.getConfigManager().isTeamTagEnabled()) {
                    plugin.getMessageManager().sendMessage(player, "feature_disabled");
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                    return;
                }
                
                String actionType = action.equals("change-tag") ? "change-tag" : "change-description";
                if (!checkActionCooldown(player, actionType, 2000)) {
                    return;
                }
                player.closeInventory();
                String setting = isTag ? "tag" : "description";
                String prompt = plugin.getMessageManager().getRawMessage("prompt_setting_change").replace("<setting>", setting);
                plugin.getMessageManager().sendRawMessage(player, prompt);
                plugin.getChatInputManager().awaitInput(player, gui, input -> {
                    if (isTag) {
                        teamManager.setTeamTag(player, input);
                    } else {
                        teamManager.setTeamDescription(player, input);
                    }
                    plugin.getTaskRunner().runOnEntity(player, gui::open);
                });
            }
        }
    }
    private void onJoinRequestGUIClick(Player player, JoinRequestGUI gui, ClickType click, ItemStack clickedItem) {
        if (clickedItem == null) return;
        ItemMeta meta = clickedItem.getItemMeta();
        if(meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(actionKey, PersistentDataType.STRING)) {
            String action = pdc.get(actionKey, PersistentDataType.STRING);
            if (action.equals("back-button")) {
                new TeamGUI(plugin, gui.getTeam(), player).open();
            } else if (action.equals("player-head")) {
                String playerUuidStr = pdc.get(new NamespacedKey(JustTeams.getInstance(), "player_uuid"), PersistentDataType.STRING);
                if (playerUuidStr != null) {
                    try {
                        UUID targetUuid = UUID.fromString(playerUuidStr);
                        if (click.isLeftClick()) {
                            teamManager.acceptJoinRequest(gui.getTeam(), targetUuid);
                        } else if (click.isRightClick()) {
                            teamManager.denyJoinRequest(gui.getTeam(), targetUuid);
                        }
                        gui.initializeItems();
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid UUID in join request GUI: " + playerUuidStr);
                    }
                }
            }
            return;
        }
    }
    
    private void onInvitesGUIClick(Player player, InvitesGUI gui, ClickType click, ItemStack clickedItem) {
        if (clickedItem == null) return;
        ItemMeta meta = clickedItem.getItemMeta();
        if (meta == null) return;
        
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(actionKey, PersistentDataType.STRING)) {
            String action = pdc.get(actionKey, PersistentDataType.STRING);
            
            switch (action) {
                case "back-button" -> {
                    Team team = teamManager.getPlayerTeam(player.getUniqueId());
                    if (team != null) {
                        new TeamGUI(plugin, team, player).open();
                    } else {
                        new NoTeamGUI(plugin, player).open();
                    }
                }
                case "close-button" -> player.closeInventory();
                case "team-icon" -> {
                    String teamIdStr = pdc.get(new NamespacedKey(plugin, "team_id"), PersistentDataType.STRING);
                    String teamName = pdc.get(new NamespacedKey(plugin, "team_name"), PersistentDataType.STRING);
                    
                    if (teamIdStr != null && teamName != null) {
                        try {
                            int teamId = Integer.parseInt(teamIdStr);
                            
                            if (click.isLeftClick()) {
                                player.closeInventory();
                                teamManager.acceptInvite(player, teamName);
                            } 
                            else if (click.isRightClick()) {
                                player.closeInventory();
                                teamManager.denyInvite(player, teamName);
                            }
                        } catch (NumberFormatException e) {
                            plugin.getLogger().warning("Invalid team ID in invites GUI: " + teamIdStr);
                        }
                    }
                }
            }
        }
    }
    private void onLeaderboardCategoryGUIClick(Player player, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        
        if ("back-button".equals(action)) {
            plugin.getTaskRunner().runAsync(() -> {
                Team team = teamManager.getPlayerTeam(player.getUniqueId());
                if (team != null) {
                    plugin.getTaskRunner().runOnEntity(player, () -> new TeamGUI(plugin, team, player).open());
                } else {
                    plugin.getTaskRunner().runOnEntity(player, () -> new NoTeamGUI(plugin, player).open());
                }
            });
            return;
        }
        
        LeaderboardViewGUI.LeaderboardType type;
        String title;
        switch (action) {
            case "top-kills" -> {
                type = LeaderboardViewGUI.LeaderboardType.KILLS;
                title = "ᴛᴏᴘ 10 ᴛᴇᴀᴍs ʙʏ ᴋɪʟʟs";
            }
            case "top-balance" -> {
                type = LeaderboardViewGUI.LeaderboardType.BALANCE;
                title = "ᴛᴏᴘ 10 ᴛᴇᴀᴍs ʙʏ ʙᴀʟᴀɴᴄᴇ";
            }
            case "top-members" -> {
                type = LeaderboardViewGUI.LeaderboardType.MEMBERS;
                title = "ᴛᴏᴘ 10 ᴛᴇᴀᴍs ʙʏ ᴍᴇᴍʙᴇʀs";
            }
            default -> { return; }
        }
        plugin.getTaskRunner().runAsync(() -> {
            Map<Integer, Team> topTeams;
            switch(type) {
                case KILLS -> topTeams = plugin.getStorageManager().getStorage().getTopTeamsByKills(10);
                case BALANCE -> topTeams = plugin.getStorageManager().getStorage().getTopTeamsByBalance(10);
                case MEMBERS -> topTeams = plugin.getStorageManager().getStorage().getTopTeamsByMembers(10);
                default -> topTeams = Map.of();
            }
            Map<Integer, Team> finalTopTeams = topTeams;
            plugin.getTaskRunner().runOnEntity(player, () -> new LeaderboardViewGUI(plugin, player, title, finalTopTeams, type).open());
        });
    }
    private void onLeaderboardViewGUIClick(Player player, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        if (action.equals("back-button")) {
            new LeaderboardCategoryGUI(plugin, player).open();
        }
    }
    private void onNoTeamGUIClick(Player player, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        switch (action) {
            case "create-team" -> {
                player.closeInventory();
                plugin.getMessageManager().sendRawMessage(player, plugin.getMessageManager().getRawMessage("prompt_team_name"));
                plugin.getChatInputManager().awaitInput(player, null, teamName -> {
                    String validationError = teamManager.validateTeamName(teamName);
                    if (validationError != null) {
                        plugin.getMessageManager().sendRawMessage(player, plugin.getMessageManager().getRawMessage("prefix") + validationError);
                        plugin.getTaskRunner().runOnEntity(player, () -> new NoTeamGUI(plugin, player).open());
                        return;
                    }
                    
                    if (plugin.getConfigManager().isTeamTagEnabled()) {
                        plugin.getMessageManager().sendRawMessage(player, plugin.getMessageManager().getRawMessage("prompt_team_tag"));
                        plugin.getChatInputManager().awaitInput(player, null, teamTag -> {
                            teamManager.createTeam(player, teamName, teamTag);
                        });
                    } else {
                        teamManager.createTeam(player, teamName, "");
                    }
                });
            }
            case "leaderboards" -> new LeaderboardCategoryGUI(plugin, player).open();
        }
    }
    private void onAdminGUIClick(Player player, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        switch (action) {
            case "back-button", "close" -> {
                player.closeInventory();
            }
            case "manage-teams" -> {
                plugin.getTaskRunner().runAsync(() -> {
                    List<Team> allTeams = teamManager.getAllTeams();
                    plugin.getTaskRunner().runOnEntity(player, () -> new AdminTeamListGUI(plugin, player, allTeams, 0).open());
                });
            }
            case "view-enderchest" -> {
                player.closeInventory();
                plugin.getChatInputManager().awaitInput(player, null, (input) -> {
                    if (input == null || input.trim().isEmpty()) {
                        plugin.getMessageManager().sendMessage(player, "invalid_input");
                        return;
                    }
                    teamManager.adminOpenEnderChest(player, input.trim());
                });
                plugin.getMessageManager().sendMessage(player, "admin_enderchest_input_prompt");
            }
            case "reload-plugin" -> {
                player.closeInventory();
                plugin.getTaskRunner().runAsync(() -> {
                    try {
                        plugin.getConfigManager().reloadConfig();
                        plugin.getTaskRunner().runOnEntity(player, () -> {
                            plugin.getMessageManager().sendMessage(player, "admin_reload_success");
                            EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
                        });
                    } catch (Exception e) {
                        plugin.getTaskRunner().runOnEntity(player, () -> {
                            plugin.getMessageManager().sendMessage(player, "admin_reload_failed");
                            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                        });
                    }
                });
            }
            default -> {
                plugin.getDebugLogger().log("Unknown admin GUI action: " + action + " from " + player.getName());
            }
        }
    }
    private void onAdminTeamListGUIClick(Player player, AdminTeamListGUI gui, ItemStack clickedItem, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        switch (action) {
            case "next-page" -> new AdminTeamListGUI(plugin, player, gui.getAllTeams(), gui.getPage() + 1).open();
            case "previous-page" -> new AdminTeamListGUI(plugin, player, gui.getAllTeams(), gui.getPage() - 1).open();
            case "back-button" -> new AdminGUI(plugin, player).open();
            case "team-head" -> {
                String displayName = clickedItem.getItemMeta().getDisplayName();
                if (displayName == null || displayName.isEmpty()) return;
                String plainName = PlainTextComponentSerializer.plainText().serialize(LegacyComponentSerializer.legacySection().deserialize(displayName));
                plugin.getTaskRunner().runAsync(() -> {
                    Team targetTeam = teamManager.getTeamByName(plainName);
                    if (targetTeam != null) {
                        plugin.getTaskRunner().runOnEntity(player, () -> new AdminTeamManageGUI(plugin, player, targetTeam).open());
                    }
                });
            }
        }
    }
    private void onAdminTeamManageGUIClick(Player player, AdminTeamManageGUI gui, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        Team team = gui.getTargetTeam();
        
        switch (action) {
            case "back-button" -> plugin.getTaskRunner().runAsync(() -> {
                List<Team> allTeams = teamManager.getAllTeams();
                plugin.getTaskRunner().runOnEntity(player, () -> new AdminTeamListGUI(plugin, player, allTeams, 0).open());
            });
            
            case "disband-team" -> new ConfirmGUI(plugin, player, "Disband " + team.getName() + "?", confirmed -> {
                if (confirmed) {
                    teamManager.adminDisbandTeam(player, team.getName());
                } else {
                    gui.open();
                }
            }).open();
            
            case "rename-team" -> {
                player.closeInventory();
                plugin.getChatInputManager().awaitInput(player, null, (input) -> {
                    if (input == null || input.trim().isEmpty()) {
                        plugin.getMessageManager().sendMessage(player, "invalid_input");
                        return;
                    }
                    String newName = input.trim();
                    
                    int minLength = plugin.getConfigManager().getMinNameLength();
                    int maxLength = plugin.getConfigManager().getMaxNameLength();
                    
                    if (newName.length() < minLength || newName.length() > maxLength) {
                        plugin.getMessageManager().sendMessage(player, "name_too_long");
                        return;
                    }
                    
                    if (!newName.matches("^[a-zA-Z0-9_]+$")) {
                        plugin.getMessageManager().sendMessage(player, "invalid_team_name");
                        return;
                    }
                    
                    if (teamManager.getTeamByName(newName) != null) {
                        plugin.getMessageManager().sendMessage(player, "team_name_exists",
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("team", newName));
                        return;
                    }
                    
                    String oldName = team.getName();
                    team.setName(newName);
                    plugin.getStorageManager().getStorage().setTeamName(team.getId(), newName);
                    
                    teamManager.publishCrossServerUpdate(team.getId(), "TEAM_UPDATED", 
                        player.getUniqueId().toString(), "name_change|" + newName);
                    
                    plugin.getMessageManager().sendMessage(player, "rename_success",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("old_name", oldName),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("new_name", newName));
                    
                    new AdminTeamManageGUI(plugin, player, team, gui.getMemberPage()).open();
                });
                plugin.getMessageManager().sendRawMessage(player, "<yellow>Enter the new team name in chat:");
            }
            
            case "edit-description" -> {
                player.closeInventory();
                plugin.getChatInputManager().awaitInput(player, null, (input) -> {
                    if (input == null || input.trim().isEmpty()) {
                        plugin.getMessageManager().sendMessage(player, "invalid_input");
                        return;
                    }
                    String newDesc = input.trim();
                    if (newDesc.length() > plugin.getConfigManager().getMaxDescriptionLength()) {
                        plugin.getMessageManager().sendMessage(player, "description_too_long");
                        return;
                    }
                    
                    team.setDescription(newDesc);
                    plugin.getStorageManager().getStorage().setTeamDescription(team.getId(), newDesc);
                    
                    teamManager.publishCrossServerUpdate(team.getId(), "TEAM_UPDATED", 
                        player.getUniqueId().toString(), "description_change");
                    
                    plugin.getMessageManager().sendMessage(player, "description_set");
                    new AdminTeamManageGUI(plugin, player, team, gui.getMemberPage()).open();
                });
                plugin.getMessageManager().sendRawMessage(player, "<yellow>Enter the new description in chat:");
            }
            
            case "edit-tag" -> {
                player.closeInventory();
                plugin.getChatInputManager().awaitInput(player, null, (input) -> {
                    if (input == null || input.trim().isEmpty()) {
                        plugin.getMessageManager().sendMessage(player, "invalid_input");
                        return;
                    }
                    String newTag = input.trim();
                    team.setTag(newTag);
                    plugin.getStorageManager().getStorage().setTeamTag(team.getId(), newTag);
                    
                    teamManager.publishCrossServerUpdate(team.getId(), "TEAM_UPDATED", 
                        player.getUniqueId().toString(), "tag_change|" + newTag);
                    
                    plugin.getMessageManager().sendMessage(player, "tag_set",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("tag", newTag));
                    new AdminTeamManageGUI(plugin, player, team, gui.getMemberPage()).open();
                });
                plugin.getMessageManager().sendRawMessage(player, "<yellow>Enter the new tag in chat:");
            }
            
            case "toggle-public" -> {
                boolean newStatus = !team.isPublic();
                team.setPublic(newStatus);
                plugin.getStorageManager().getStorage().setPublicStatus(team.getId(), newStatus);
                
                teamManager.publishCrossServerUpdate(team.getId(), "PUBLIC_STATUS_CHANGED", 
                    player.getUniqueId().toString(), String.valueOf(newStatus));
                
                plugin.getMessageManager().sendRawMessage(player, newStatus ? "<green>Team is now public" : "<red>Team is now private");
                new AdminTeamManageGUI(plugin, player, team, gui.getMemberPage()).open();
            }
            
            case "toggle-pvp" -> {
                boolean newStatus = !team.isPvpEnabled();
                team.setPvpEnabled(newStatus);
                plugin.getStorageManager().getStorage().setPvpStatus(team.getId(), newStatus);
                
                teamManager.publishCrossServerUpdate(team.getId(), "PVP_STATUS_CHANGED", 
                    player.getUniqueId().toString(), String.valueOf(newStatus));
                
                plugin.getMessageManager().sendRawMessage(player, newStatus ? "<green>PvP enabled" : "<red>PvP disabled");
                new AdminTeamManageGUI(plugin, player, team, gui.getMemberPage()).open();
            }
            
            case "edit-balance" -> {
                player.closeInventory();
                plugin.getChatInputManager().awaitInput(player, null, (input) -> {
                    if (input == null || input.trim().isEmpty()) {
                        plugin.getMessageManager().sendMessage(player, "invalid_input");
                        return;
                    }
                    try {
                        double newBalance = Double.parseDouble(input.trim());
                        if (newBalance < 0) {
                            plugin.getMessageManager().sendRawMessage(player, "<red>Balance cannot be negative");
                            return;
                        }
                        double oldBalance = team.getBalance();
                        team.setBalance(newBalance);
                        plugin.getStorageManager().getStorage().updateTeamBalance(team.getId(), newBalance);
                        
                        teamManager.publishCrossServerUpdate(team.getId(), "ADMIN_BALANCE_SET", 
                            player.getUniqueId().toString(), String.valueOf(newBalance));
                        
                        plugin.getMessageManager().sendRawMessage(player, "<green>Balance set to <white>" + String.format("%.2f", newBalance));
                        new AdminTeamManageGUI(plugin, player, team, gui.getMemberPage()).open();
                    } catch (NumberFormatException e) {
                        plugin.getMessageManager().sendRawMessage(player, "<red>Invalid number format");
                    }
                });
                plugin.getMessageManager().sendRawMessage(player, "<yellow>Enter the new balance:");
            }
            
            case "edit-stats" -> {
                player.closeInventory();
                plugin.getChatInputManager().awaitInput(player, null, (input) -> {
                    if (input == null || input.trim().isEmpty()) {
                        plugin.getMessageManager().sendMessage(player, "invalid_input");
                        return;
                    }
                    String[] parts = input.trim().split(" ");
                    if (parts.length != 2) {
                        plugin.getMessageManager().sendRawMessage(player, "<red>Usage: <kills> <deaths>");
                        return;
                    }
                    try {
                        int kills = Integer.parseInt(parts[0]);
                        int deaths = Integer.parseInt(parts[1]);
                        if (kills < 0 || deaths < 0) {
                            plugin.getMessageManager().sendRawMessage(player, "<red>Stats cannot be negative");
                            return;
                        }
                        team.setKills(kills);
                        team.setDeaths(deaths);
                        plugin.getStorageManager().getStorage().updateTeamStats(team.getId(), kills, deaths);
                        
                        teamManager.publishCrossServerUpdate(team.getId(), "ADMIN_STATS_SET", 
                            player.getUniqueId().toString(), kills + ":" + deaths);
                        
                        plugin.getMessageManager().sendRawMessage(player, "<green>Stats updated: <white>" + kills + " kills, " + deaths + " deaths");
                        new AdminTeamManageGUI(plugin, player, team, gui.getMemberPage()).open();
                    } catch (NumberFormatException e) {
                        plugin.getMessageManager().sendRawMessage(player, "<red>Invalid number format");
                    }
                });
                plugin.getMessageManager().sendRawMessage(player, "<yellow>Enter kills and deaths (e.g., '100 50'):");
            }
            
            case "view-enderchest" -> {
                player.closeInventory();
                teamManager.adminOpenEnderChest(player, team.getName());
            }
            
            case "next-members" -> new AdminTeamManageGUI(plugin, player, team, gui.getMemberPage() + 1).open();
            case "prev-members" -> new AdminTeamManageGUI(plugin, player, team, gui.getMemberPage() - 1).open();
            
            case "refresh" -> new AdminTeamManageGUI(plugin, player, team, gui.getMemberPage()).open();
            
            default -> {
                if (action.startsWith("member-")) {
                    String uuidStr = action.substring(7);
                    try {
                        UUID memberUuid = UUID.fromString(uuidStr);
                        TeamPlayer member = team.getMember(memberUuid);
                        if (member == null) {
                            plugin.getMessageManager().sendRawMessage(player, "<red>Member not found!");
                            return;
                        }
                        
                        if (memberUuid.equals(team.getOwnerUuid())) {
                            plugin.getMessageManager().sendRawMessage(player, "<red>Cannot edit the team owner!");
                            return;
                        }
                        new AdminMemberEditGUI(plugin, player, team, member).open();
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid UUID in admin member click: " + uuidStr);
                    }
                }
            }
        }
    }
    
    private void onAdminMemberEditGUIClick(Player player, AdminMemberEditGUI gui, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        Team team = gui.getTeam();
        TeamPlayer member = gui.getMember();
        
        switch (action) {
            case "back-button" -> new AdminTeamManageGUI(plugin, player, team, 0).open();
            
            case "kick-member" -> {
                plugin.getTaskRunner().runAsync(() -> {
                    plugin.getStorageManager().getStorage().removeMemberFromTeam(member.getPlayerUuid());
                    
                    OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(member.getPlayerUuid());
                    String targetName = targetPlayer.getName() != null ? targetPlayer.getName() : "Unknown";
                    teamManager.publishCrossServerUpdate(team.getId(), "ADMIN_MEMBER_KICK", 
                        player.getUniqueId().toString(), member.getPlayerUuid().toString());
                    
                    plugin.getTaskRunner().run(() -> {
                        team.removeMember(member.getPlayerUuid());
                        plugin.getMessageManager().sendRawMessage(player, "<green>Kicked <white>" + targetName + " <green>from the team");
                        new AdminTeamManageGUI(plugin, player, team, 0).open();
                    });
                });
                
                plugin.getTaskRunner().runTaskLater(() -> 
                    new AdminTeamManageGUI(plugin, player, team, 0).open(), 10L);
            }
            
            case "toggle-withdraw" -> {
                boolean newValue = !member.canWithdraw();
                member.setCanWithdraw(newValue);
                try {
                    plugin.getStorageManager().getStorage().updateMemberPermission(team.getId(), member.getPlayerUuid(), "withdraw", newValue);
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to update withdraw permission: " + e.getMessage());
                }
                
                teamManager.publishCrossServerUpdate(team.getId(), "ADMIN_PERMISSION_UPDATE", 
                    player.getUniqueId().toString(), member.getPlayerUuid() + ":withdraw:" + newValue);
                
                plugin.getMessageManager().sendRawMessage(player, (newValue ? "<green>Enabled" : "<red>Disabled") + " <white>withdraw permission");
                new AdminMemberEditGUI(plugin, player, team, member).open();
            }
            
            case "toggle-enderchest" -> {
                boolean newValue = !member.canUseEnderChest();
                member.setCanUseEnderChest(newValue);
                try {
                    plugin.getStorageManager().getStorage().updateMemberPermission(team.getId(), member.getPlayerUuid(), "enderchest", newValue);
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to update enderchest permission: " + e.getMessage());
                }
                
                teamManager.publishCrossServerUpdate(team.getId(), "ADMIN_PERMISSION_UPDATE", 
                    player.getUniqueId().toString(), member.getPlayerUuid() + ":enderchest:" + newValue);
                
                plugin.getMessageManager().sendRawMessage(player, (newValue ? "<green>Enabled" : "<red>Disabled") + " <white>enderchest permission");
                new AdminMemberEditGUI(plugin, player, team, member).open();
            }
            
            case "toggle-sethome" -> {
                boolean newValue = !member.canSetHome();
                member.setCanSetHome(newValue);
                try {
                    plugin.getStorageManager().getStorage().updateMemberPermission(team.getId(), member.getPlayerUuid(), "sethome", newValue);
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to update sethome permission: " + e.getMessage());
                }
                
                teamManager.publishCrossServerUpdate(team.getId(), "ADMIN_PERMISSION_UPDATE", 
                    player.getUniqueId().toString(), member.getPlayerUuid() + ":sethome:" + newValue);
                
                plugin.getMessageManager().sendRawMessage(player, (newValue ? "<green>Enabled" : "<red>Disabled") + " <white>set home permission");
                new AdminMemberEditGUI(plugin, player, team, member).open();
            }
            
            case "toggle-usehome" -> {
                boolean newValue = !member.canUseHome();
                member.setCanUseHome(newValue);
                try {
                    plugin.getStorageManager().getStorage().updateMemberPermission(team.getId(), member.getPlayerUuid(), "usehome", newValue);
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to update usehome permission: " + e.getMessage());
                }
                
                teamManager.publishCrossServerUpdate(team.getId(), "ADMIN_PERMISSION_UPDATE", 
                    player.getUniqueId().toString(), member.getPlayerUuid() + ":usehome:" + newValue);
                
                plugin.getMessageManager().sendRawMessage(player, (newValue ? "<green>Enabled" : "<red>Disabled") + " <white>use home permission");
                new AdminMemberEditGUI(plugin, player, team, member).open();
            }
            
            case "promote-member" -> {
                if (member.getRole() == TeamRole.OWNER) {
                    plugin.getMessageManager().sendRawMessage(player, "<red>Member is already the owner!");
                    return;
                } else if (member.getRole() == TeamRole.CO_OWNER) {
                    plugin.getMessageManager().sendRawMessage(player, "<red>Member is already a co-owner!");
                    return;
                }
                
                member.setRole(TeamRole.CO_OWNER);
                plugin.getStorageManager().getStorage().updateMemberRole(team.getId(), member.getPlayerUuid(), TeamRole.CO_OWNER);
                
                teamManager.publishCrossServerUpdate(team.getId(), "ADMIN_MEMBER_PROMOTE", 
                    player.getUniqueId().toString(), member.getPlayerUuid().toString());
                
                plugin.getMessageManager().sendRawMessage(player, "<green>Promoted member to Co-Owner");
                new AdminMemberEditGUI(plugin, player, team, member).open();
            }
            
            case "demote-member" -> {
                if (member.getRole() == TeamRole.MEMBER) {
                    plugin.getMessageManager().sendRawMessage(player, "<red>Member is already at the lowest rank!");
                    return;
                } else if (member.getRole() == TeamRole.OWNER) {
                    plugin.getMessageManager().sendRawMessage(player, "<red>Cannot demote the owner!");
                    return;
                }
                
                member.setRole(TeamRole.MEMBER);
                plugin.getStorageManager().getStorage().updateMemberRole(team.getId(), member.getPlayerUuid(), TeamRole.MEMBER);
                
                teamManager.publishCrossServerUpdate(team.getId(), "ADMIN_MEMBER_DEMOTE", 
                    player.getUniqueId().toString(), member.getPlayerUuid().toString());
                
                plugin.getMessageManager().sendRawMessage(player, "<green>Demoted member to regular Member");
                new AdminMemberEditGUI(plugin, player, team, member).open();
            }
        }
    }
    private void onConfirmGUIClick(ConfirmGUI gui, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        if (action.equals("confirm")) {
            gui.handleConfirm();
        } else if (action.equals("cancel")) {
            gui.handleCancel();
        }
    }
    private void onWarpsGUIClick(Player player, WarpsGUI gui, ClickType click, ItemStack clickedItem, PersistentDataContainer pdc) {
        if (!pdc.has(actionKey, PersistentDataType.STRING)) return;
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        if (action.equals("back-button")) {
            new TeamGUI(plugin, gui.getTeam(), player).open();
        } else if (action.equals("warp_item")) {
            String warpName = pdc.get(new NamespacedKey(JustTeams.getInstance(), "warp_name"), PersistentDataType.STRING);
            if (warpName != null) {
                if (click.isLeftClick()) {
                    if (!plugin.getFeatureRestrictionManager().canAffordAndPay(player, "warp")) {
                        return;
                    }
                    plugin.getTeamManager().teleportToTeamWarp(player, warpName, null);
                    player.closeInventory();
                } else if (click.isRightClick()) {
                    plugin.getTeamManager().deleteTeamWarp(player, warpName);
                    gui.initializeItems();
                }
            }
        }
    }
    private void onBlacklistGUIClick(Player player, BlacklistGUI gui, ClickType click, ItemStack clickedItem, PersistentDataContainer pdc) {
        if (plugin.getConfigManager().isDebugEnabled()) {
            plugin.getDebugLogger().log("=== BLACKLIST GUI CLICK DEBUG ===");
            plugin.getDebugLogger().log("Player: " + player.getName());
            plugin.getDebugLogger().log("Click type: " + click);
            plugin.getDebugLogger().log("Clicked item: " + (clickedItem != null ? clickedItem.getType() : "null"));
            plugin.getDebugLogger().log("PDC has action key: " + pdc.has(actionKey, PersistentDataType.STRING));
        }
        if (!pdc.has(actionKey, PersistentDataType.STRING)) {
            plugin.getLogger().warning("No action key found in PDC for blacklist click");
            return;
        }
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        plugin.getLogger().info("Action retrieved: " + action);
        if (action.equals("back-button")) {
            plugin.getLogger().info("Back button clicked, opening team GUI");
            new TeamGUI(plugin, gui.getTeam(), player).open();
        } else if (action.startsWith("remove-blacklist:")) {
            plugin.getLogger().info("Remove blacklist action detected: " + action);
            if (!checkActionCooldown(player, "remove-blacklist", 2000)) {
                plugin.getLogger().info("Rate limit hit for blacklist removal by " + player.getName());
                return;
            }
            String uuidString = action.substring("remove-blacklist:".length());
            plugin.getLogger().info("UUID string extracted: " + uuidString);
            UUID targetUuid;
            try {
                targetUuid = UUID.fromString(uuidString);
                plugin.getLogger().info("UUID parsed successfully: " + targetUuid);
                plugin.getLogger().info("Team ID: " + gui.getTeam().getId());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID format in blacklist removal action: " + uuidString);
                return;
            }
            final BlacklistGUI finalGui = gui;
            final UUID finalTargetUuid = targetUuid;
            plugin.getLogger().info("Starting async blacklist removal...");
            plugin.getTaskRunner().runAsync(() -> {
                try {
                    plugin.getLogger().info("Executing blacklist removal in async thread for " + finalTargetUuid);
                    plugin.getLogger().info("Storage manager: " + plugin.getStorageManager());
                    plugin.getLogger().info("Storage: " + plugin.getStorageManager().getStorage());
                    boolean success = plugin.getStorageManager().getStorage().removePlayerFromBlacklist(
                        finalGui.getTeam().getId(), finalTargetUuid);
                    plugin.getLogger().info("Blacklist removal result: " + success + " for " + finalTargetUuid);
                    if (success) {
                        plugin.getLogger().info("Blacklist removal successful, refreshing GUI...");
                        plugin.getTaskRunner().runOnEntity(player, () -> {
                            try {
                                plugin.getMessageManager().sendMessage(player, "player_removed_from_blacklist",
                                    Placeholder.unparsed("target", Bukkit.getOfflinePlayer(finalTargetUuid).getName()));
                                plugin.getLogger().info("Success message sent, now refreshing GUI for " + player.getName());
                                finalGui.refresh();
                                plugin.getLogger().info("GUI refresh called successfully");
                            } catch (Exception e) {
                                plugin.getLogger().severe("Error in sync thread for blacklist removal: " + e.getMessage());
                                plugin.getLogger().severe("Error in GUI action: " + e.getMessage());
                            }
                        });
                    } else {
                        plugin.getLogger().warning("Blacklist removal failed in database");
                        plugin.getTaskRunner().runOnEntity(player, () -> {
                            plugin.getMessageManager().sendMessage(player, "remove_blacklist_failed");
                        });
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Error removing player from blacklist: " + e.getMessage());
                    plugin.getLogger().severe("Error in GUI action: " + e.getMessage());
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        plugin.getMessageManager().sendMessage(player, "remove_blacklist_failed");
                    });
                }
            });
        } else {
            plugin.getLogger().warning("Unknown action in blacklist GUI: " + action);
        }
        if (plugin.getConfigManager().isDebugEnabled()) {
            plugin.getDebugLogger().log("=== END BLACKLIST GUI CLICK DEBUG ===");
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        boolean isOurGui = holder instanceof IRefreshableGUI || holder instanceof NoTeamGUI || holder instanceof ConfirmGUI ||
                holder instanceof AdminGUI || holder instanceof AdminTeamListGUI || holder instanceof AdminTeamManageGUI ||
                holder instanceof TeamSettingsGUI || holder instanceof LeaderboardCategoryGUI || holder instanceof LeaderboardViewGUI ||
                holder instanceof JoinRequestGUI || holder instanceof InvitesGUI || holder instanceof WarpsGUI || holder instanceof BlacklistGUI;

        if (isOurGui) {
            event.setCancelled(true);
        }
    }
}
