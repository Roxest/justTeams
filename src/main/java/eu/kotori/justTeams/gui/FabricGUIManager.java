package eu.kotori.justTeams.gui;

import com.mojang.authlib.GameProfile;
import eu.kotori.justTeams.JustTeamsFabric;
import eu.kotori.justTeams.commands.FabricTeamCommand;
import eu.kotori.justTeams.storage.FabricStorageManager.MemberRecord;
import eu.kotori.justTeams.storage.FabricStorageManager.TeamRecord;
import eu.kotori.justTeams.storage.FabricStorageManager.WarpRecord;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.yaml.snakeyaml.Yaml;

public class FabricGUIManager {

    private static final Logger LOGGER = Logger.getLogger(FabricGUIManager.class.getName());
    private final JustTeamsFabric plugin;
    private MinecraftServer server;
    private Map<String, Object> guiConfig = new HashMap<>();

    public FabricGUIManager(JustTeamsFabric plugin) {
        this.plugin = plugin;
        this.loadConfig();
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer getServer() {
        return server != null ? server : plugin.getServer();
    }

    @SuppressWarnings("unchecked")
    public void loadConfig() {
        File configFile = plugin.getDataFolder().resolve("gui.yml").toFile();
        Yaml yaml = new Yaml();
        if (configFile.exists()) {
            try (InputStream is = new FileInputStream(configFile)) {
                this.guiConfig = yaml.load(is);
                LOGGER.info("✓ Loaded gui.yml from config/JustTeams/gui.yml successfully!");
                return;
            } catch (Exception e) {
                LOGGER.warning("Could not read gui.yml from disk: " + e.getMessage());
            }
        }
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("gui.yml")) {
            if (is != null) {
                this.guiConfig = yaml.load(is);
                LOGGER.info("✓ Loaded default gui.yml from mod resources successfully!");
            }
        } catch (Exception e) {
            LOGGER.severe("Failed to load default gui.yml: " + e.getMessage());
        }
    }

    private ServerPlayer resolvePlayer(UUID uuid) {
        MinecraftServer srv = getServer();
        if (srv != null) {
            return srv.getPlayerList().getPlayer(uuid);
        }
        return null;
    }

    // ==========================================
    // Public Open GUI API
    // ==========================================

    public void openTeamGUI(UUID playerUuid) {
        ServerPlayer player = resolvePlayer(playerUuid);
        if (player != null) openTeamGUI(player);
    }

    public void openTeamGUI(ServerPlayer player) {
        if (player == null) return;
        try {
            UUID uuid = player.getUUID();
            TeamRecord team = plugin.getTeamManager().getPlayerTeam(uuid);

            if (team == null) {
                openNoTeamGUI(player);
                return;
            }

            Map<String, Object> guiSec = getSection(this.guiConfig, "team-gui");
            int size = getInt(guiSec, "size", 54);
            String rawTitle = getString(guiSec, "title", "ᴛᴇᴀᴍ - <members>/<max_members>");

            List<MemberRecord> members = plugin.getStorageManager().getTeamMembers(team.id);
            if (members == null) members = Collections.emptyList();
            Map<String, String> placeholders = buildPlaceholders(player, team, members);

            MenuType<?> menuType = getMenuType(size);
            SimpleGui gui = new SimpleGui(menuType, player, false);
            gui.setTitle(formatComponent(rawTitle, placeholders));

            // 1. Fill background item
            applyFillItem(gui, size, getSection(guiSec, "fill-item"), placeholders);

            // 2. Dummy items
            applyDummyItems(gui, size, getSection(guiSec, "dummy-items"), placeholders);

            // 3. Functional Items
            boolean isOwner = uuid.equals(team.ownerUuid);
            boolean homeIsSet = team.homeLocation != null && !team.homeLocation.isEmpty();

            Map<String, Object> itemsSec = getSection(guiSec, "items");
            for (Map.Entry<String, Object> entry : itemsSec.entrySet()) {
                String key = entry.getKey();
                if (!(entry.getValue() instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> itemData = (Map<String, Object>) entry.getValue();
                if (!getBoolean(itemData, "enabled", true)) continue;

                // Handle mutually exclusive item variants
                if (key.equals("disband-button") && !isOwner) continue;
                if (key.equals("leave-button") && isOwner) continue;
                if (key.equals("join-requests") && !isOwner) continue;
                if (key.equals("join-requests-locked") && isOwner) continue;
                if (key.equals("team-settings-button") && !isOwner) continue;
                if (key.equals("settings-locked") && isOwner) continue;
                if (key.equals("bank-locked") || key.equals("ender-chest-locked") || key.equals("warps-locked") || key.equals("pvp-toggle-locked")) continue;

                if (key.equals("player-head")) {
                    renderPlayerHeads(gui, itemData, members, placeholders);
                    continue;
                }

                List<Integer> slots = getSlots(itemData);
                if (slots.isEmpty()) continue;

                Item item = resolveItem(getString(itemData, "material", "STONE"));
                String name = getString(itemData, "name", key);
                
                // Determine lore: handle lore-set vs lore-not-set for home
                @SuppressWarnings("unchecked")
                List<String> lore = (List<String>) itemData.get("lore");
                if (key.equals("home")) {
                    @SuppressWarnings("unchecked")
                    List<String> homeLore = (List<String>) itemData.get(homeIsSet ? "lore-set" : "lore-not-set");
                    if (homeLore != null) lore = homeLore;
                }

                int cmd = getInt(itemData, "custom-model-data", 0);
                boolean enchanted = getBoolean(itemData, "enchanted", false);
                String action = getString(itemData, "action", key);

                GuiElementBuilder builder = new GuiElementBuilder(item)
                    .setName(formatComponent(name, placeholders));

                if (lore != null) {
                    for (String l : lore) {
                        builder.addLoreLine(formatComponent(l, placeholders));
                    }
                }
                if (cmd > 0) builder.setCustomModelData(cmd);
                if (enchanted) builder.glow(true);

                builder.setCallback((index, type, actionType, slotGui) -> {
                    handleTeamAction(action, player, team, slotGui);
                });

                for (int slot : slots) {
                    if (slot >= 0 && slot < size) {
                        gui.setSlot(slot, builder.build());
                    }
                }
            }

            gui.open();
        } catch (Throwable t) {
            LOGGER.severe("Error rendering team GUI: " + t.getMessage());
            t.printStackTrace();
            player.sendSystemMessage(Component.literal("§c[JustTeams] An error occurred opening the team GUI. Check server logs."));
        }
    }

    public void openNoTeamGUI(ServerPlayer player) {
        if (player == null) return;
        try {
            Map<String, Object> guiSec = getSection(this.guiConfig, "no-team-gui");
            int size = getInt(guiSec, "size", 27);
            String rawTitle = getString(guiSec, "title", "ᴛᴇᴀᴍ ᴍᴇɴᴜ");

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("<player>", player.getScoreboardName());

            MenuType<?> menuType = getMenuType(size);
            SimpleGui gui = new SimpleGui(menuType, player, false);
            gui.setTitle(formatComponent(rawTitle, placeholders));

            applyFillItem(gui, size, getSection(guiSec, "fill-item"), placeholders);
            applyDummyItems(gui, size, getSection(guiSec, "dummy-items"), placeholders);

            Map<String, Object> itemsSec = getSection(guiSec, "items");
            for (Map.Entry<String, Object> entry : itemsSec.entrySet()) {
                String key = entry.getKey();
                if (!(entry.getValue() instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> itemData = (Map<String, Object>) entry.getValue();
                if (!getBoolean(itemData, "enabled", true)) continue;

                List<Integer> slots = getSlots(itemData);
                if (slots.isEmpty()) continue;

                Item item = resolveItem(getString(itemData, "material", "STONE"));
                String name = getString(itemData, "name", key);
                @SuppressWarnings("unchecked")
                List<String> lore = (List<String>) itemData.get("lore");
                int cmd = getInt(itemData, "custom-model-data", 0);
                boolean enchanted = getBoolean(itemData, "enchanted", false);
                String action = getString(itemData, "action", key);

                GuiElementBuilder builder = new GuiElementBuilder(item)
                    .setName(formatComponent(name, placeholders));

                if (lore != null) {
                    for (String l : lore) {
                        builder.addLoreLine(formatComponent(l, placeholders));
                    }
                }
                if (cmd > 0) builder.setCustomModelData(cmd);
                if (enchanted) builder.glow(true);

                builder.setCallback((index, type, actionType, slotGui) -> {
                    handleNoTeamAction(action, player, slotGui);
                });

                for (int slot : slots) {
                    if (slot >= 0 && slot < size) {
                        gui.setSlot(slot, builder.build());
                    }
                }
            }

            gui.open();
        } catch (Throwable t) {
            LOGGER.severe("Error rendering no-team GUI: " + t.getMessage());
            t.printStackTrace();
            player.sendSystemMessage(Component.literal("§c[JustTeams] An error occurred opening the menu. Check server logs."));
        }
    }

    public void startTeamCreationProcess(ServerPlayer player) {
        if (player == null) return;
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
        player.sendSystemMessage(Component.literal("§e✦ §bCreate Your Team §e✦"));
        player.sendSystemMessage(Component.literal("§7Please type your desired §fTeam Name §7in chat:"));
        player.sendSystemMessage(Component.literal("§8(Or type §ccancel §8to abort)"));
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));

        plugin.getChatInputManager().awaitInput(player, teamName -> {
            if (teamName.equalsIgnoreCase("cancel")) {
                player.sendSystemMessage(Component.literal("§cTeam creation cancelled."));
                openNoTeamGUI(player);
                return;
            }

            if (teamName.length() < 3 || teamName.length() > 16 || !teamName.matches("^[a-zA-Z0-9_]+$")) {
                player.sendSystemMessage(Component.literal("§cTeam name must be 3-16 alphanumeric characters!"));
                startTeamCreationProcess(player);
                return;
            }

            if (plugin.getStorageManager().getTeamByName(teamName) != null) {
                player.sendSystemMessage(Component.literal("§cA team with name §e" + teamName + " §calready exists!"));
                startTeamCreationProcess(player);
                return;
            }

            // Prompt for Tag
            player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
            player.sendSystemMessage(Component.literal("§eTeam Name: §a" + teamName));
            player.sendSystemMessage(Component.literal("§7Please type your §fTeam Tag §7in chat (max 6 chars, or §fnone§7):"));
            player.sendSystemMessage(Component.literal("§8(Or type §ccancel §8to abort)"));
            player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));

            plugin.getChatInputManager().awaitInput(player, tagInput -> {
                if (tagInput.equalsIgnoreCase("cancel")) {
                    player.sendSystemMessage(Component.literal("§cTeam creation cancelled."));
                    openNoTeamGUI(player);
                    return;
                }

                String tag = (tagInput.equalsIgnoreCase("none") || tagInput.isEmpty()) ? teamName : tagInput;
                if (tag.length() > 6) tag = tag.substring(0, 6);

                boolean ok = plugin.getTeamManager().createTeam(teamName, tag, player.getUUID(), player.getScoreboardName());
                if (ok) {
                    player.sendSystemMessage(Component.literal("§a✔ Team §e" + teamName + " §7[" + tag + "] §acreated successfully!"));
                    openTeamGUI(player);
                } else {
                    player.sendSystemMessage(Component.literal("§cFailed to create team. You may already be in a team."));
                    openNoTeamGUI(player);
                }
            });
        });
    }

    public void openWarpsGUI(UUID playerUuid) {
        ServerPlayer player = resolvePlayer(playerUuid);
        if (player != null) openWarpsGUI(player);
    }

    public void openWarpsGUI(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        TeamRecord team = plugin.getTeamManager().getPlayerTeam(uuid);
        if (team == null) {
            openNoTeamGUI(player);
            return;
        }

        Map<String, Object> guiSec = getSection(this.guiConfig, "warps-gui");
        int size = getInt(guiSec, "size", 54);
        String rawTitle = getString(guiSec, "title", "ᴛᴇᴀᴍ ᴡᴀʀᴘs");

        List<WarpRecord> warps = plugin.getTeamManager().listWarps(uuid);
        Map<String, String> placeholders = buildPlaceholders(player, team, Collections.emptyList());
        placeholders.put("<warps_count>", String.valueOf(warps.size()));

        MenuType<?> menuType = getMenuType(size);
        SimpleGui gui = new SimpleGui(menuType, player, false);
        gui.setTitle(formatComponent(rawTitle, placeholders));

        applyFillItem(gui, size, getSection(guiSec, "fill-item"), placeholders);
        applyDummyItems(gui, size, getSection(guiSec, "dummy-items"), placeholders);

        // Display warps
        int slot = 10;
        for (WarpRecord warp : warps) {
            if (slot >= size - 9) break;
            if ((slot % 9) == 8) slot += 2; // skip edge columns for clean aesthetic

            GuiElementBuilder builder = new GuiElementBuilder(Items.COMPASS)
                .setName(Component.literal("§6Warp: §e" + warp.name))
                .addLoreLine(Component.literal(warp.password != null && !warp.password.isEmpty() ? "§cProtected (Password Required)" : "§aPublic to team"))
                .addLoreLine(Component.literal(""))
                .addLoreLine(Component.literal("§eClick to teleport!"));

            builder.setCallback((idx, type, actionType, slotGui) -> {
                slotGui.close();
                FabricTeamCommand.teleportPlayer(player, warp.location);
                player.sendSystemMessage(Component.literal("§aTeleported to warp §e" + warp.name + "§a!"));
            });

            gui.setSlot(slot++, builder.build());
        }

        // Back button (middle of bottom row)
        int backSlot = size > 9 ? size - 5 : 0;
        GuiElementBuilder backBtn = new GuiElementBuilder(Items.BARRIER)
            .setName(Component.literal("§c« Back to Team Menu"))
            .setCallback((idx, type, actionType, slotGui) -> openTeamGUI(player));
        gui.setSlot(backSlot, backBtn.build());

        gui.open();
    }

    public void openBankGUI(UUID playerUuid) {
        ServerPlayer player = resolvePlayer(playerUuid);
        if (player != null) openBankGUI(player);
    }

    public void openBankGUI(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        TeamRecord team = plugin.getTeamManager().getPlayerTeam(uuid);
        if (team == null) {
            openNoTeamGUI(player);
            return;
        }

        Map<String, Object> guiSec = getSection(this.guiConfig, "bank-gui");
        int size = getInt(guiSec, "size", 27);
        String rawTitle = getString(guiSec, "title", "ᴛᴇᴀᴍ ʙᴀɴᴋ");

        Map<String, String> placeholders = buildPlaceholders(player, team, Collections.emptyList());

        MenuType<?> menuType = getMenuType(size);
        SimpleGui gui = new SimpleGui(menuType, player, false);
        gui.setTitle(formatComponent(rawTitle, placeholders));

        applyFillItem(gui, size, getSection(guiSec, "fill-item"), placeholders);
        applyDummyItems(gui, size, getSection(guiSec, "dummy-items"), placeholders);

        // Bank Balance Info
        GuiElementBuilder balanceItem = new GuiElementBuilder(Items.SUNFLOWER)
            .setName(Component.literal("§6Team Balance: §a$" + String.format("%.2f", team.balance)))
            .addLoreLine(Component.literal("§7Deposit money to upgrade your team!"))
            .addLoreLine(Component.literal(""));
        gui.setSlot(13, balanceItem.build());

        // Deposit Info Button
        GuiElementBuilder depositBtn = new GuiElementBuilder(Items.EMERALD)
            .setName(Component.literal("§aDeposit Funds"))
            .addLoreLine(Component.literal("§7Click to view deposit command"))
            .setCallback((idx, type, actionType, slotGui) -> {
                slotGui.close();
                player.sendSystemMessage(Component.literal("§a[Team Bank] §eType §f/team bank deposit <amount> §eto deposit."));
            });
        gui.setSlot(11, depositBtn.build());

        // Withdraw Info Button
        GuiElementBuilder withdrawBtn = new GuiElementBuilder(Items.REDSTONE)
            .setName(Component.literal("§cWithdraw Funds"))
            .addLoreLine(Component.literal("§7Click to view withdraw command"))
            .setCallback((idx, type, actionType, slotGui) -> {
                slotGui.close();
                player.sendSystemMessage(Component.literal("§a[Team Bank] §eType §f/team bank withdraw <amount> §eto withdraw."));
            });
        gui.setSlot(15, withdrawBtn.build());

        // Back button
        int backSlot = size - 5;
        GuiElementBuilder backBtn = new GuiElementBuilder(Items.BARRIER)
            .setName(Component.literal("§c« Back to Team Menu"))
            .setCallback((idx, type, actionType, slotGui) -> openTeamGUI(player));
        gui.setSlot(backSlot, backBtn.build());

        gui.open();
    }

    public void openLeaderboardsGUI(UUID playerUuid) {
        ServerPlayer player = resolvePlayer(playerUuid);
        if (player != null) openLeaderboardsGUI(player);
    }

    public void openLeaderboardsGUI(ServerPlayer player) {
        if (player == null) return;
        List<TeamRecord> tops = plugin.getTeamManager().getTopTeams(10);

        int size = 54;
        MenuType<?> menuType = getMenuType(size);
        SimpleGui gui = new SimpleGui(menuType, player, false);
        gui.setTitle(Component.literal("§6Top Teams Leaderboard"));

        GuiElement fillElement = new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE)
            .setName(Component.literal(" "))
            .build();
        for (int i = 0; i < size; i++) {
            gui.setSlot(i, fillElement);
        }

        int[] displaySlots = new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
        int rank = 1;
        for (TeamRecord t : tops) {
            if (rank > displaySlots.length) break;
            int targetSlot = displaySlots[rank - 1];

            Item icon = rank == 1 ? Items.GOLD_BLOCK : (rank == 2 ? Items.IRON_BLOCK : (rank == 3 ? Items.COPPER_BLOCK : Items.EMERALD));
            GuiElementBuilder entry = new GuiElementBuilder(icon)
                .setName(Component.literal("§e#" + rank + " §f" + t.name + (t.tag != null && !t.tag.isEmpty() ? " §7[" + t.tag + "]" : "")))
                .addLoreLine(Component.literal("§7Points: §e" + t.points))
                .addLoreLine(Component.literal("§7Bank Balance: §a$" + String.format("%.2f", t.balance)))
                .addLoreLine(Component.literal("§7Tier: §b" + t.tier));

            gui.setSlot(targetSlot, entry.build());
            rank++;
        }

        int backSlot = size - 5;
        GuiElementBuilder backBtn = new GuiElementBuilder(Items.BARRIER)
            .setName(Component.literal("§c« Back"))
            .setCallback((idx, type, actionType, slotGui) -> openTeamGUI(player));
        gui.setSlot(backSlot, backBtn.build());

        gui.open();
    }

    public void openAllyGUI(UUID playerUuid) {
        ServerPlayer player = resolvePlayer(playerUuid);
        if (player != null) openTeamGUI(player);
    }

    public void openUpgradesGUI(UUID playerUuid) {
        ServerPlayer player = resolvePlayer(playerUuid);
        if (player != null) openTeamGUI(player);
    }

    public void openQuestGUI(UUID playerUuid) {
        ServerPlayer player = resolvePlayer(playerUuid);
        if (player != null) openTeamGUI(player);
    }

    public void openInvitesGUI(UUID playerUuid) {
        ServerPlayer player = resolvePlayer(playerUuid);
        if (player != null) openTeamGUI(player);
    }

    public void openBlacklistGUI(UUID playerUuid) {
        ServerPlayer player = resolvePlayer(playerUuid);
        if (player != null) openTeamGUI(player);
    }

    public void openJoinRequestGUI(UUID playerUuid) {
        ServerPlayer player = resolvePlayer(playerUuid);
        if (player != null) openTeamGUI(player);
    }

    public void openMemberEditGUI(UUID playerUuid, UUID targetMemberUuid) {
        ServerPlayer player = resolvePlayer(playerUuid);
        if (player != null) openTeamGUI(player);
    }

    public void closeGUI(UUID playerUuid) {
        // SimpleGui handles closing
    }

    // ==========================================
    // Action Dispatcher
    // ==========================================

    private void handleNoTeamAction(String action, ServerPlayer player, eu.pb4.sgui.api.gui.SlotGuiInterface slotGui) {
        if (action == null) return;
        switch (action.toLowerCase()) {
            case "create-team" -> {
                slotGui.close();
                startTeamCreationProcess(player);
            }
            case "leaderboards" -> {
                openLeaderboardsGUI(player);
            }
            default -> {
                slotGui.close();
            }
        }
    }

    private void handleTeamAction(String action, ServerPlayer player, TeamRecord team, eu.pb4.sgui.api.gui.SlotGuiInterface slotGui) {
        if (action == null) return;
        switch (action.toLowerCase()) {
            case "home" -> {
                slotGui.close();
                if (team.homeLocation != null && !team.homeLocation.isEmpty()) {
                    FabricTeamCommand.teleportPlayer(player, team.homeLocation);
                    player.sendSystemMessage(Component.literal("§aTeleported to team home!"));
                } else {
                    player.sendSystemMessage(Component.literal("§cYour team home is not set! Set it with /team sethome"));
                }
            }
            case "warps" -> openWarpsGUI(player);
            case "bank" -> openBankGUI(player);
            case "allies" -> openWarpsGUI(player);
            case "ender-chest" -> {
                slotGui.close();
                player.sendSystemMessage(Component.literal("§aOpening team ender chest..."));
            }
            case "sort" -> {
                player.sendSystemMessage(Component.literal("§6Toggled team member sort mode."));
            }
            case "pvp-toggle" -> {
                boolean ok = plugin.getTeamManager().togglePvP(player.getUUID());
                if (ok) {
                    team.pvpEnabled = !team.pvpEnabled;
                    player.sendSystemMessage(Component.literal("§6Team PvP friendly fire is now: " + (team.pvpEnabled ? "§aEnabled" : "§cDisabled")));
                    openTeamGUI(player); // refresh GUI to update sword status
                }
            }
            case "disband-button" -> {
                slotGui.close();
                boolean ok = plugin.getTeamManager().disbandTeam(player.getUUID());
                if (ok) {
                    player.sendSystemMessage(Component.literal("§cYour team has been disbanded."));
                } else {
                    player.sendSystemMessage(Component.literal("§cCould not disband team. Only the owner can disband."));
                }
            }
            case "leave-button" -> {
                slotGui.close();
                boolean ok = plugin.getTeamManager().leaveTeam(player.getUUID());
                if (ok) {
                    player.sendSystemMessage(Component.literal("§eYou have left the team."));
                } else {
                    player.sendSystemMessage(Component.literal("§cCould not leave team. Owners cannot leave; disband the team instead."));
                }
            }
            default -> {
                LOGGER.info("Action triggered in team GUI: " + action);
            }
        }
    }

    // ==========================================
    // Config Parsing & Formatting Helpers
    // ==========================================

    private void renderPlayerHeads(SimpleGui gui, Map<String, Object> itemData, List<MemberRecord> members, Map<String, String> basePlaceholders) {
        List<Integer> headSlots = getSlots(itemData);
        if (headSlots.isEmpty()) {
            headSlots = defaultMemberSlots();
        }
        String onlineFormat = getString(itemData, "online-name-format", "<gradient:#4C9DDE:#4C96D2><status_indicator><role_icon><player></gradient>");
        String offlineFormat = getString(itemData, "offline-name-format", "<gray><status_indicator><role_icon><player>");
        @SuppressWarnings("unchecked")
        List<String> loreTemplate = (List<String>) itemData.get("lore");

        int slotIdx = 0;
        for (MemberRecord m : members) {
            if (slotIdx >= headSlots.size()) break;
            int slot = headSlots.get(slotIdx++);

            String playerName = "Member";
            boolean isOnline = false;
            if (getServer() != null) {
                ServerPlayer memPl = getServer().getPlayerList().getPlayer(m.playerUuid);
                if (memPl != null) {
                    playerName = memPl.getScoreboardName();
                    isOnline = true;
                }
            }
            if (m.playerName != null && !m.playerName.isEmpty()) {
                playerName = m.playerName;
            }

            String format = isOnline ? onlineFormat : offlineFormat;

            Map<String, String> mPlaceholders = new HashMap<>(basePlaceholders);
            mPlaceholders.put("<player>", playerName);
            mPlaceholders.put("<role>", m.role != null ? m.role : "MEMBER");
            mPlaceholders.put("<status_indicator>", isOnline ? "● " : "○ ");
            mPlaceholders.put("<role_icon>", m.role != null && m.role.equalsIgnoreCase("OWNER") ? "★ " : "");
            mPlaceholders.put("<joindate>", m.joinedAt != null ? m.joinedAt.toString() : "N/A");
            mPlaceholders.put("<server>", "Survival");

            GuiElementBuilder headBuilder = new GuiElementBuilder(Items.PLAYER_HEAD)
                .setName(formatComponent(format, mPlaceholders));

            try {
                GameProfile profile = new GameProfile(m.playerUuid, playerName);
                headBuilder.setSkullOwner(profile, getServer());
            } catch (Throwable t) {
                LOGGER.warning("Could not set skull owner profile: " + t.getMessage());
            }

            if (loreTemplate != null) {
                for (String l : loreTemplate) {
                    headBuilder.addLoreLine(formatComponent(l, mPlaceholders));
                }
            }
            gui.setSlot(slot, headBuilder.build());
        }
    }

    private void applyFillItem(SimpleGui gui, int size, Map<String, Object> fillSec, Map<String, String> placeholders) {
        if (fillSec == null || fillSec.isEmpty()) return;
        Item fillItem = resolveItem(getString(fillSec, "material", "GRAY_STAINED_GLASS_PANE"));
        String fillName = getString(fillSec, "name", " ");
        GuiElement fillElement = new GuiElementBuilder(fillItem)
            .setName(formatComponent(fillName, placeholders))
            .build();
        for (int i = 0; i < size; i++) {
            gui.setSlot(i, fillElement);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyDummyItems(SimpleGui gui, int size, Map<String, Object> dummySec, Map<String, String> placeholders) {
        if (dummySec == null || dummySec.isEmpty()) return;
        for (Map.Entry<String, Object> entry : dummySec.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            Map<String, Object> dData = (Map<String, Object>) entry.getValue();

            List<Integer> dSlots = getSlots(dData);
            if (dSlots.isEmpty()) continue;

            Item item = resolveItem(getString(dData, "material", "GRAY_STAINED_GLASS_PANE"));
            String name = getString(dData, "name", " ");
            List<String> lore = (List<String>) dData.get("lore");
            int cmd = getInt(dData, "custom-model-data", 0);
            boolean enchanted = getBoolean(dData, "enchanted", false);

            GuiElementBuilder builder = new GuiElementBuilder(item)
                .setName(formatComponent(name, placeholders));
            if (lore != null) {
                for (String l : lore) {
                    builder.addLoreLine(formatComponent(l, placeholders));
                }
            }
            if (cmd > 0) builder.setCustomModelData(cmd);
            if (enchanted) builder.glow(true);

            for (int slot : dSlots) {
                if (slot >= 0 && slot < size) {
                    gui.setSlot(slot, builder.build());
                }
            }
        }
    }

    private Map<String, String> buildPlaceholders(ServerPlayer player, TeamRecord team, List<MemberRecord> members) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("<player>", player != null ? player.getScoreboardName() : "Player");
        if (team != null) {
            placeholders.put("<team_name>", team.name != null ? team.name : "Team");
            placeholders.put("<team_tag>", team.tag != null ? team.tag : "");
            placeholders.put("<balance>", String.format("%.2f", team.balance));
            placeholders.put("<tier>", String.valueOf(team.tier));
            placeholders.put("<points>", String.valueOf(team.points));
            placeholders.put("<members>", String.valueOf(members != null ? members.size() : 1));
            placeholders.put("<max_members>", "30");
            placeholders.put("<pvp>", team.pvpEnabled ? "Enabled" : "Disabled");
            placeholders.put("<status>", team.pvpEnabled ? "Enabled" : "Disabled");
            placeholders.put("<status_indicator>", "● ");
            boolean isOwner = (player != null && team.ownerUuid != null && player.getUUID().equals(team.ownerUuid));
            placeholders.put("<role>", isOwner ? "OWNER" : "MEMBER");
            placeholders.put("<role_icon>", isOwner ? "★ " : "");
            placeholders.put("<sort_status_join_date>", "");
            placeholders.put("<sort_status_alphabetical>", "");
            placeholders.put("<sort_status_online_status>", "");
        }
        return placeholders;
    }

    private Component formatComponent(String rawText, Map<String, String> placeholders) {
        if (rawText == null || rawText.isEmpty()) return Component.empty();
        String text = rawText;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue() != null ? entry.getValue() : "";
                text = text.replace(k, v);
            }
        }
        try {
            MiniMessage mm = MiniMessage.miniMessage();
            net.kyori.adventure.text.Component adv = mm.deserialize(text);
            String legacy = LegacyComponentSerializer.legacySection().serialize(adv);
            return Component.literal(legacy);
        } catch (Exception e) {
            return Component.literal(text.replace("&", "§"));
        }
    }

    private Item resolveItem(String materialName) {
        if (materialName == null || materialName.isEmpty()) return Items.AIR;
        String id = materialName.toLowerCase().trim().replace(" ", "_");

        if (id.equals("player_head") || id.equals("skull_item")) return Items.PLAYER_HEAD;
        if (id.equals("writable_book") || id.equals("book_and_quill")) return Items.WRITABLE_BOOK;

        ResourceLocation loc = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (loc != null && BuiltInRegistries.ITEM.containsKey(loc)) {
            return BuiltInRegistries.ITEM.get(loc);
        }
        return Items.STONE;
    }

    private MenuType<?> getMenuType(int size) {
        if (size <= 9) return MenuType.GENERIC_9x1;
        if (size <= 18) return MenuType.GENERIC_9x2;
        if (size <= 27) return MenuType.GENERIC_9x3;
        if (size <= 36) return MenuType.GENERIC_9x4;
        if (size <= 45) return MenuType.GENERIC_9x5;
        return MenuType.GENERIC_9x6;
    }

    private List<Integer> defaultMemberSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int i = 9; i <= 44; i++) slots.add(i);
        return slots;
    }

    private List<Integer> getSlots(Map<String, Object> itemData) {
        List<Integer> slots = new ArrayList<>();
        Object slotObj = itemData.get("slot");
        if (slotObj instanceof Number) {
            slots.add(((Number) slotObj).intValue());
        } else if (slotObj instanceof List) {
            for (Object o : (List<?>) slotObj) {
                if (o instanceof Number) slots.add(((Number) o).intValue());
                else if (o instanceof String) {
                    try { slots.add(Integer.parseInt((String) o)); } catch (Exception ignored) {}
                }
            }
        }
        Object slotsObj = itemData.get("slots");
        if (slotsObj instanceof List) {
            for (Object o : (List<?>) slotsObj) {
                if (o instanceof Number) slots.add(((Number) o).intValue());
                else if (o instanceof String) {
                    try { slots.add(Integer.parseInt((String) o)); } catch (Exception ignored) {}
                }
            }
        }
        return slots;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSection(Map<String, Object> parent, String key) {
        if (parent == null) return Collections.emptyMap();
        Object obj = parent.get(key);
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return Collections.emptyMap();
    }

    private String getString(Map<String, Object> map, String key, String def) {
        if (map == null) return def;
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : def;
    }

    private int getInt(Map<String, Object> map, String key, int def) {
        if (map == null) return def;
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (Exception ignored) {}
        }
        return def;
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean def) {
        if (map == null) return def;
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return def;
    }
}
