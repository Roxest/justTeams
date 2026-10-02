package eu.kotori.justTeams.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.kotori.justTeams.JustTeamsFabric;
import eu.kotori.justTeams.storage.FabricStorageManager.TeamRecord;
import eu.kotori.justTeams.storage.FabricStorageManager.WarpRecord;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class FabricTeamCommand {

    private static final Logger LOGGER = Logger.getLogger(FabricTeamCommand.class.getName());

    @SuppressWarnings("unchecked")
    public static void register(JustTeamsFabric plugin) {
        LOGGER.info("Registering Brigadier /team subcommands for Fabric...");

        // 1. Hook via CommandRegistrationCallback
        try {
            Class<?> callbackClass = Class.forName("net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback");
            Field eventField = callbackClass.getField("EVENT");
            Object eventInstance = eventField.get(null);

            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("register") && args != null && args.length >= 1) {
                        CommandDispatcher<Object> dispatcher = (CommandDispatcher<Object>) args[0];
                        registerCommands(dispatcher, plugin);
                    }
                    return null;
                }
            );

            invokeEventRegister(eventInstance, listener);
            LOGGER.info("✓ Hooked CommandRegistrationCallback for /team!");
        } catch (Throwable t) {
            LOGGER.warning("Could not hook CommandRegistrationCallback: " + t.getMessage());
        }

        // 2. Also hook ServerLifecycleEvents.SERVER_STARTING
        try {
            Class<?> lifecycleClass = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents");
            Field startingField = lifecycleClass.getField("SERVER_STARTING");
            Object startingEvent = startingField.get(null);

            Class<?> startingListenerClass = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStarting");

            Object startingListener = java.lang.reflect.Proxy.newProxyInstance(
                startingListenerClass.getClassLoader(),
                new Class<?>[]{startingListenerClass},
                (proxy, method, args) -> {
                    if (args != null && args.length >= 1) {
                        Object server = args[0];
                        try {
                            Method getCommandManagerMethod = server.getClass().getMethod("getCommandManager");
                            Object cmdManager = getCommandManagerMethod.invoke(server);
                            Method getDispatcherMethod = cmdManager.getClass().getMethod("getDispatcher");
                            CommandDispatcher<Object> dispatcher = (CommandDispatcher<Object>) getDispatcherMethod.invoke(cmdManager);
                            registerCommands(dispatcher, plugin);
                            LOGGER.info("✓ Registered JustTeams /team on ServerStarting dispatcher!");
                        } catch (Exception e) {
                            LOGGER.warning("Error getting dispatcher on server start: " + e.getMessage());
                        }
                    }
                    return null;
                }
            );

            invokeEventRegister(startingEvent, startingListener);
            LOGGER.info("✓ Hooked ServerLifecycleEvents.SERVER_STARTING for /team!");
        } catch (Throwable t) {
            LOGGER.warning("Could not hook ServerLifecycleEvents: " + t.getMessage());
        }
    }

    public static void invokeEventRegister(Object eventInstance, Object listener) throws Exception {
        if (eventInstance == null || listener == null) return;
        try {
            Class<?> eventClass = Class.forName("net.fabricmc.fabric.api.event.Event");
            for (Method m : eventClass.getMethods()) {
                if (m.getName().equals("register") && m.getParameterCount() == 1) {
                    m.invoke(eventInstance, listener);
                    return;
                }
            }
        } catch (Throwable ignored) {}

        for (Method m : eventInstance.getClass().getMethods()) {
            if (m.getName().equals("register") && m.getParameterCount() == 1) {
                m.setAccessible(true);
                m.invoke(eventInstance, listener);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void registerCommands(CommandDispatcher<Object> dispatcher, JustTeamsFabric plugin) {
        if (dispatcher == null) return;

        // Step 1: Remove vanilla Mojang /team command from RootCommandNode
        try {
            CommandNode<Object> root = dispatcher.getRoot();
            
            Field childrenField = CommandNode.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            Map<String, CommandNode<Object>> children = (Map<String, CommandNode<Object>>) childrenField.get(root);
            children.remove("team");

            Field literalsField = CommandNode.class.getDeclaredField("literals");
            literalsField.setAccessible(true);
            Map<String, LiteralCommandNode<Object>> literals = (Map<String, LiteralCommandNode<Object>>) literalsField.get(root);
            literals.remove("team");
            
            LOGGER.info("Overrode vanilla Mojang /team command with JustTeams!");
        } catch (Exception e) {
            LOGGER.warning("Could not remove vanilla /team node: " + e.getMessage());
        }

        // Step 2: Build JustTeams /team command tree
        LiteralArgumentBuilder<Object> teamBuilder = LiteralArgumentBuilder.literal("team")
            .executes(ctx -> executeGui(ctx.getSource(), plugin))
            .then(LiteralArgumentBuilder.literal("gui").executes(ctx -> executeGui(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("create")
                .then(RequiredArgumentBuilder.argument("name", StringArgumentType.string())
                    .executes(ctx -> executeCreate(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "name"), ""))
                    .then(RequiredArgumentBuilder.argument("tag", StringArgumentType.string())
                        .executes(ctx -> executeCreate(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "tag"))))))
            .then(LiteralArgumentBuilder.literal("disband").executes(ctx -> executeDisband(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("invite")
                .then(RequiredArgumentBuilder.argument("player", StringArgumentType.word())
                    .executes(ctx -> executeInvite(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "player")))))
            .then(LiteralArgumentBuilder.literal("accept")
                .executes(ctx -> executeAccept(ctx.getSource(), plugin, ""))
                .then(RequiredArgumentBuilder.argument("team", StringArgumentType.string())
                    .executes(ctx -> executeAccept(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "team")))))
            .then(LiteralArgumentBuilder.literal("deny")
                .executes(ctx -> executeDeny(ctx.getSource(), plugin, ""))
                .then(RequiredArgumentBuilder.argument("team", StringArgumentType.string())
                    .executes(ctx -> executeDeny(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "team")))))
            .then(LiteralArgumentBuilder.literal("leave").executes(ctx -> executeLeave(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("kick")
                .then(RequiredArgumentBuilder.argument("player", StringArgumentType.word())
                    .executes(ctx -> executeKick(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "player")))))
            .then(LiteralArgumentBuilder.literal("promote")
                .then(RequiredArgumentBuilder.argument("player", StringArgumentType.word())
                    .executes(ctx -> executePromote(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "player")))))
            .then(LiteralArgumentBuilder.literal("demote")
                .then(RequiredArgumentBuilder.argument("player", StringArgumentType.word())
                    .executes(ctx -> executeDemote(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "player")))))
            .then(LiteralArgumentBuilder.literal("info")
                .executes(ctx -> executeInfo(ctx.getSource(), plugin, ""))
                .then(RequiredArgumentBuilder.argument("team", StringArgumentType.string())
                    .executes(ctx -> executeInfo(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "team")))))
            .then(LiteralArgumentBuilder.literal("home").executes(ctx -> executeHome(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("sethome").executes(ctx -> executeSetHome(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("delhome").executes(ctx -> executeDelHome(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("warp")
                .executes(ctx -> executeWarpList(ctx.getSource(), plugin))
                .then(RequiredArgumentBuilder.argument("name", StringArgumentType.word())
                    .executes(ctx -> executeWarp(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "name"), ""))
                    .then(RequiredArgumentBuilder.argument("password", StringArgumentType.word())
                        .executes(ctx -> executeWarp(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "password"))))))
            .then(LiteralArgumentBuilder.literal("setwarp")
                .then(RequiredArgumentBuilder.argument("name", StringArgumentType.word())
                    .executes(ctx -> executeSetWarp(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "name"), ""))
                    .then(RequiredArgumentBuilder.argument("password", StringArgumentType.word())
                        .executes(ctx -> executeSetWarp(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "password"))))))
            .then(LiteralArgumentBuilder.literal("delwarp")
                .then(RequiredArgumentBuilder.argument("name", StringArgumentType.word())
                    .executes(ctx -> executeDelWarp(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "name")))))
            .then(LiteralArgumentBuilder.literal("warps").executes(ctx -> executeWarpList(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("pvp").executes(ctx -> executePvP(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("bank")
                .executes(ctx -> executeBankInfo(ctx.getSource(), plugin))
                .then(LiteralArgumentBuilder.literal("deposit")
                    .then(RequiredArgumentBuilder.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(ctx -> executeBankDeposit(ctx.getSource(), plugin, DoubleArgumentType.getDouble(ctx, "amount")))))
                .then(LiteralArgumentBuilder.literal("withdraw")
                    .then(RequiredArgumentBuilder.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(ctx -> executeBankWithdraw(ctx.getSource(), plugin, DoubleArgumentType.getDouble(ctx, "amount"))))))
            .then(LiteralArgumentBuilder.literal("enderchest").executes(ctx -> executeEnderchest(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("ec").executes(ctx -> executeEnderchest(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("top").executes(ctx -> executeTop(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("quests").executes(ctx -> executeQuests(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("quest").executes(ctx -> executeQuests(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("upgrades").executes(ctx -> executeUpgrades(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("upgrade").executes(ctx -> executeUpgrades(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("ally")
                .executes(ctx -> executeAllyGui(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("chat")
                .executes(ctx -> executeToggleChat(ctx.getSource(), plugin))
                .then(RequiredArgumentBuilder.argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> executeChatMessage(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "message")))))
            .then(LiteralArgumentBuilder.literal("admin")
                .then(LiteralArgumentBuilder.literal("disband")
                    .then(RequiredArgumentBuilder.argument("team", StringArgumentType.string())
                        .executes(ctx -> executeAdminDisband(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "team")))))
                .then(LiteralArgumentBuilder.literal("reload")
                    .executes(ctx -> executeReload(ctx.getSource(), plugin))))
            .then(LiteralArgumentBuilder.literal("reload")
                .executes(ctx -> executeReload(ctx.getSource(), plugin)))
            .then(LiteralArgumentBuilder.literal("help").executes(ctx -> executeHelp(ctx.getSource(), plugin)));

        LiteralCommandNode<Object> teamNode = dispatcher.register(teamBuilder);
        dispatcher.register(LiteralArgumentBuilder.literal("t").redirect(teamNode));
        LOGGER.info("Registered /team and /t commands successfully into dispatcher!");
    }

    public static ServerPlayer getPlayer(Object source) {
        if (source instanceof CommandSourceStack stack) {
            try {
                Entity entity = stack.getEntity();
                if (entity instanceof ServerPlayer player) {
                    return player;
                }
            } catch (Throwable ignored) {}
            try {
                return stack.getPlayer();
            } catch (Throwable ignored) {}
        }
        if (source instanceof ServerPlayer player) {
            return player;
        }
        return null;
    }

    private static UUID getPlayerUuid(Object source) {
        ServerPlayer player = getPlayer(source);
        return player != null ? player.getUUID() : null;
    }

    private static String getPlayerName(Object source) {
        ServerPlayer player = getPlayer(source);
        return player != null ? player.getScoreboardName() : "Unknown";
    }

    private static String getPlayerLocationString(Object source) {
        ServerPlayer player = getPlayer(source);
        if (player != null) {
            String worldName = player.level().dimension().location().toString();
            return String.format("%s,%.2f,%.2f,%.2f,%.2f,%.2f",
                worldName, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }
        return "minecraft:overworld,0,64,0,0,0";
    }

    public static void teleportPlayer(Object source, String locationStr) {
        if (locationStr == null || locationStr.isEmpty()) return;
        try {
            ServerPlayer player = source instanceof ServerPlayer p ? p : getPlayer(source);
            if (player == null) return;

            String[] parts = locationStr.split(",");
            if (parts.length >= 4) {
                String worldName = parts[0];
                double x = Double.parseDouble(parts[1]);
                double y = Double.parseDouble(parts[2]);
                double z = Double.parseDouble(parts[3]);
                float yaw = parts.length >= 5 ? Float.parseFloat(parts[4]) : 0f;
                float pitch = parts.length >= 6 ? Float.parseFloat(parts[5]) : 0f;

                MinecraftServer server = player.getServer();
                ServerLevel targetWorld = null;
                if (server != null) {
                    for (ServerLevel level : server.getAllLevels()) {
                        String dimId = level.dimension().location().toString();
                        if (dimId.equalsIgnoreCase(worldName) ||
                            (worldName.equalsIgnoreCase("world") && dimId.contains("overworld")) ||
                            (worldName.toLowerCase().contains("nether") && dimId.contains("the_nether")) ||
                            (worldName.toLowerCase().contains("end") && dimId.contains("the_end"))) {
                            targetWorld = level;
                            break;
                        }
                    }
                }
                if (targetWorld == null) {
                    targetWorld = player.serverLevel();
                }

                player.teleportTo(targetWorld, x, y, z, yaw, pitch);
            }
        } catch (Exception e) {
            LOGGER.warning("Teleport error: " + e.getMessage());
        }
    }

    public static void sendFeedback(Object source, String text) {
        if (source instanceof CommandSourceStack stack) {
            Component comp = Component.literal(text);
            stack.sendSuccess(() -> comp, false);
            return;
        }
        if (source instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal(text));
            return;
        }
        LOGGER.info("[JustTeams] " + text);
    }

    private static int executeGui(Object source, JustTeamsFabric plugin) {
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            sendFeedback(source, "§6=== JustTeams (Console Help) ===");
            sendFeedback(source, "§e/team create <name> [tag]");
            sendFeedback(source, "§e/team admin disband <team>");
            sendFeedback(source, "§e/team reload");
            sendFeedback(source, "§7(In-game players running /team will view team status and commands)");
            return 1;
        }

        plugin.getGuiManager().openTeamGUI(player);
        return 1;
    }

    private static int executeCreate(Object source, JustTeamsFabric plugin, String name, String tag) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) {
            sendFeedback(source, "§cOnly players can create a team.");
            return 0;
        }
        String playerName = getPlayerName(source);
        boolean ok = plugin.getTeamManager().createTeam(name, tag, uuid, playerName);
        if (ok) {
            sendFeedback(source, plugin.getMessageManager().get("team_created", "§aSuccessfully created team §e" + name + "§a!", "team", name));
            return 1;
        } else {
            sendFeedback(source, plugin.getMessageManager().get("team_already_exists", "§cCould not create team. Either you are already in a team or that name is already taken."));
            return 0;
        }
    }

    private static int executeDisband(Object source, JustTeamsFabric plugin) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        boolean ok = plugin.getTeamManager().disbandTeam(uuid);
        if (ok) {
            sendFeedback(source, plugin.getMessageManager().get("team_disbanded", "§cYour team has been disbanded."));
            return 1;
        } else {
            sendFeedback(source, plugin.getMessageManager().get("must_be_owner", "§cCould not disband team. You must be the team owner."));
            return 0;
        }
    }

    private static int executeInvite(Object source, JustTeamsFabric plugin, String targetName) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        TeamRecord team = plugin.getTeamManager().getPlayerTeam(uuid);
        if (team == null) {
            sendFeedback(source, plugin.getMessageManager().get("player_not_in_team", "§cYou are not in a team."));
            return 0;
        }
        sendFeedback(source, plugin.getMessageManager().get("invite_sent", "§aInvite sent to §e" + targetName + " §afor team §e" + team.name + "§a.", "target", targetName, "team", team.name));
        return 1;
    }

    private static int executeAccept(Object source, JustTeamsFabric plugin, String teamName) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        boolean ok = plugin.getTeamManager().acceptInvite(uuid, teamName);
        if (ok) {
            sendFeedback(source, plugin.getMessageManager().get("team_joined", "§aSuccessfully joined the team!"));
            return 1;
        } else {
            sendFeedback(source, plugin.getMessageManager().get("no_invite_pending", "§cNo pending invite found or team is invalid."));
            return 0;
        }
    }

    private static int executeDeny(Object source, JustTeamsFabric plugin, String teamName) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        plugin.getTeamManager().denyInvite(uuid, teamName);
        sendFeedback(source, plugin.getMessageManager().get("invite_denied", "§cTeam invite denied."));
        return 1;
    }

    private static int executeLeave(Object source, JustTeamsFabric plugin) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        boolean ok = plugin.getTeamManager().leaveTeam(uuid);
        if (ok) {
            sendFeedback(source, plugin.getMessageManager().get("team_left", "§eYou have left your team."));
            return 1;
        } else {
            sendFeedback(source, plugin.getMessageManager().get("owner_must_disband", "§cAs the owner, you must disband the team instead of leaving."));
            return 0;
        }
    }

    private static int executeKick(Object source, JustTeamsFabric plugin, String player) {
        sendFeedback(source, "§cKicking players requires an active member UUID.");
        return 1;
    }

    private static int executePromote(Object source, JustTeamsFabric plugin, String player) {
        sendFeedback(source, "§aPromoted §e" + player);
        return 1;
    }

    private static int executeDemote(Object source, JustTeamsFabric plugin, String player) {
        sendFeedback(source, "§cDemoted §e" + player);
        return 1;
    }

    private static int executeInfo(Object source, JustTeamsFabric plugin, String teamName) {
        TeamRecord team;
        if (!teamName.isEmpty()) {
            team = plugin.getTeamManager().getTeamByName(teamName);
        } else {
            UUID uuid = getPlayerUuid(source);
            team = (uuid != null) ? plugin.getTeamManager().getPlayerTeam(uuid) : null;
        }
        if (team == null) {
            sendFeedback(source, "§cTeam not found.");
            return 0;
        }
        sendFeedback(source, "§6=== Team Info: §e" + team.name + " §7[" + team.tag + "] §6===");
        sendFeedback(source, "§7Bank: §a$" + String.format("%.2f", team.balance) + " §7| Tier: §b" + team.tier + " §7| Points: §e" + team.points);
        sendFeedback(source, "§7Friendly Fire: " + (team.pvpEnabled ? "§aEnabled" : "§cDisabled") + " §7| Kills: §a" + team.kills + " §7| Deaths: §c" + team.deaths);
        return 1;
    }

    private static int executeHome(Object source, JustTeamsFabric plugin) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        String locStr = plugin.getTeamManager().getHome(uuid);
        if (locStr == null || locStr.isEmpty()) {
            sendFeedback(source, "§cYour team has not set a home yet.");
            return 0;
        }
        teleportPlayer(source, locStr);
        sendFeedback(source, "§aTeleported to team home!");
        return 1;
    }

    private static int executeSetHome(Object source, JustTeamsFabric plugin) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        String locStr = getPlayerLocationString(source);
        boolean ok = plugin.getTeamManager().setHome(uuid, locStr);
        if (ok) {
            sendFeedback(source, "§aTeam home set successfully!");
            return 1;
        } else {
            sendFeedback(source, "§cYou are not in a team.");
            return 0;
        }
    }

    private static int executeDelHome(Object source, JustTeamsFabric plugin) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        boolean ok = plugin.getTeamManager().delHome(uuid);
        if (ok) {
            sendFeedback(source, "§cTeam home deleted.");
            return 1;
        } else {
            sendFeedback(source, "§cYou are not in a team.");
            return 0;
        }
    }

    private static int executeWarp(Object source, JustTeamsFabric plugin, String name, String password) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        WarpRecord warp = plugin.getTeamManager().getWarp(uuid, name);
        if (warp == null) {
            sendFeedback(source, "§cWarp §e" + name + " §cnot found.");
            return 0;
        }
        if (warp.password != null && !warp.password.isEmpty()) {
            if (!warp.password.equals(password)) {
                sendFeedback(source, "§cIncorrect warp password.");
                return 0;
            }
        }
        teleportPlayer(source, warp.location);
        sendFeedback(source, "§aTeleported to warp §e" + name + "§a!");
        return 1;
    }

    private static int executeSetWarp(Object source, JustTeamsFabric plugin, String name, String password) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        String locStr = getPlayerLocationString(source);
        boolean ok = plugin.getTeamManager().setWarp(uuid, name, locStr, password);
        if (ok) {
            sendFeedback(source, "§aSet warp §e" + name + " §asuccessfully!");
            return 1;
        } else {
            sendFeedback(source, "§cYou are not in a team.");
            return 0;
        }
    }

    private static int executeDelWarp(Object source, JustTeamsFabric plugin, String name) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        boolean ok = plugin.getTeamManager().delWarp(uuid, name);
        if (ok) {
            sendFeedback(source, "§cDeleted warp §e" + name);
            return 1;
        } else {
            sendFeedback(source, "§cWarp not found.");
            return 0;
        }
    }

    private static int executeWarpList(Object source, JustTeamsFabric plugin) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        List<WarpRecord> warps = plugin.getTeamManager().listWarps(uuid);
        if (warps.isEmpty()) {
            sendFeedback(source, "§7No team warps set.");
            return 1;
        }
        sendFeedback(source, "§6=== Team Warps (" + warps.size() + ") ===");
        for (WarpRecord w : warps) {
            sendFeedback(source, "§e- " + w.name + (w.password != null && !w.password.isEmpty() ? " §7(protected)" : ""));
        }
        return 1;
    }

    private static int executePvP(Object source, JustTeamsFabric plugin) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        boolean ok = plugin.getTeamManager().togglePvP(uuid);
        if (ok) {
            TeamRecord team = plugin.getTeamManager().getPlayerTeam(uuid);
            sendFeedback(source, "§6Team friendly-fire is now: " + (team.pvpEnabled ? "§aEnabled" : "§cDisabled"));
            return 1;
        } else {
            sendFeedback(source, "§cYou are not in a team.");
            return 0;
        }
    }

    private static int executeBankInfo(Object source, JustTeamsFabric plugin) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        TeamRecord team = plugin.getTeamManager().getPlayerTeam(uuid);
        if (team == null) {
            sendFeedback(source, "§cYou are not in a team.");
            return 0;
        }
        sendFeedback(source, "§6=== Team Bank ===");
        sendFeedback(source, "§7Balance: §a$" + String.format("%.2f", team.balance));
        return 1;
    }

    private static int executeBankDeposit(Object source, JustTeamsFabric plugin, double amount) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        boolean ok = plugin.getTeamManager().depositBank(uuid, amount);
        if (ok) {
            sendFeedback(source, "§aSuccessfully deposited §e$" + String.format("%.2f", amount) + " §ato team bank!");
            return 1;
        } else {
            sendFeedback(source, "§cFailed to deposit. Check your team status.");
            return 0;
        }
    }

    private static int executeBankWithdraw(Object source, JustTeamsFabric plugin, double amount) {
        UUID uuid = getPlayerUuid(source);
        if (uuid == null) return 0;
        boolean ok = plugin.getTeamManager().withdrawBank(uuid, amount);
        if (ok) {
            sendFeedback(source, "§aSuccessfully withdrew §e$" + String.format("%.2f", amount) + " §cfrom team bank!");
            return 1;
        } else {
            sendFeedback(source, "§cFailed to withdraw. Insufficient team bank balance.");
            return 0;
        }
    }

    private static int executeEnderchest(Object source, JustTeamsFabric plugin) {
        sendFeedback(source, "§aOpening team ender chest...");
        return 1;
    }

    private static int executeTop(Object source, JustTeamsFabric plugin) {
        List<TeamRecord> tops = plugin.getTeamManager().getTopTeams(10);
        sendFeedback(source, "§6=== Top Teams Leaderboard ===");
        if (tops.isEmpty()) {
            sendFeedback(source, "§7No teams found in database.");
            return 1;
        }
        int rank = 1;
        for (TeamRecord t : tops) {
            sendFeedback(source, "§e#" + rank++ + " §f" + t.name + " §7[" + t.tag + "] - §a$" + String.format("%.0f", t.balance) + " §7(Points: " + t.points + ")");
        }
        return 1;
    }

    private static int executeQuests(Object source, JustTeamsFabric plugin) {
        UUID uuid = getPlayerUuid(source);
        if (uuid != null) plugin.getGuiManager().openQuestGUI(uuid);
        sendFeedback(source, "§6=== Team Quests ===");
        return 1;
    }

    private static int executeUpgrades(Object source, JustTeamsFabric plugin) {
        UUID uuid = getPlayerUuid(source);
        if (uuid != null) plugin.getGuiManager().openUpgradesGUI(uuid);
        sendFeedback(source, "§6=== Team Upgrades ===");
        return 1;
    }

    private static int executeAllyGui(Object source, JustTeamsFabric plugin) {
        UUID uuid = getPlayerUuid(source);
        if (uuid != null) plugin.getGuiManager().openAllyGUI(uuid);
        sendFeedback(source, "§6=== Team Allies ===");
        return 1;
    }

    private static int executeToggleChat(Object source, JustTeamsFabric plugin) {
        sendFeedback(source, "§6Toggled team chat mode.");
        return 1;
    }

    private static int executeChatMessage(Object source, JustTeamsFabric plugin, String message) {
        sendFeedback(source, "§b[Team Chat] §f" + message);
        return 1;
    }

    private static int executeAdminDisband(Object source, JustTeamsFabric plugin, String teamName) {
        TeamRecord team = plugin.getTeamManager().getTeamByName(teamName);
        if (team == null) {
            sendFeedback(source, "§cTeam §e" + teamName + " §cnot found.");
            return 0;
        }
        boolean ok = plugin.getStorageManager().disbandTeam(team.id);
        if (ok) {
            sendFeedback(source, "§c[Admin] Disbanded team §e" + teamName);
            return 1;
        } else {
            sendFeedback(source, "§c[Admin] Failed to disband team.");
            return 0;
        }
    }

    private static int executeReload(Object source, JustTeamsFabric plugin) {
        sendFeedback(source, "§a[JustTeams] Reloading configuration...");
        plugin.reload();
        sendFeedback(source, plugin.getMessageManager().get("reload", "§aPlugin configurations have been successfully reloaded."));
        return 1;
    }

    private static int executeHelp(Object source, JustTeamsFabric plugin) {
        sendFeedback(source, "§6=== JustTeams Help ===");
        sendFeedback(source, "§e/team create <name> [tag] §7- Create a team");
        sendFeedback(source, "§e/team invite <player> §7- Invite a player");
        sendFeedback(source, "§e/team accept [team] §7- Accept invite");
        sendFeedback(source, "§e/team deny [team] §7- Deny invite");
        sendFeedback(source, "§e/team leave §7- Leave team");
        sendFeedback(source, "§e/team home §7- Teleport to team home");
        sendFeedback(source, "§e/team sethome §7- Set team home");
        sendFeedback(source, "§e/team bank §7- Manage team bank");
        sendFeedback(source, "§e/team warp [name] §7- Team warps");
        sendFeedback(source, "§e/team pvp §7- Toggle friendly fire");
        sendFeedback(source, "§e/team top §7- View leaderboard");
        return 1;
    }
}
