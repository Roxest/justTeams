package eu.kotori.justTeams.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import eu.kotori.justTeams.JustTeamsFabric;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

public class FabricTeamMessageCommand {

    private static final Logger LOGGER = Logger.getLogger(FabricTeamMessageCommand.class.getName());

    @SuppressWarnings("unchecked")
    public static void register(JustTeamsFabric plugin) {
        LOGGER.info("Registering Brigadier shortcut team chat commands (/tc, /teammsg) for Fabric...");

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
                        registerChatCommands(dispatcher, plugin);
                    }
                    return null;
                }
            );

            FabricTeamCommand.invokeEventRegister(eventInstance, listener);
            LOGGER.info("✓ Hooked CommandRegistrationCallback for /tc!");
        } catch (Throwable t) {
            LOGGER.warning("Could not hook CommandRegistrationCallback for chat commands: " + t.getMessage());
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
                            registerChatCommands(dispatcher, plugin);
                            LOGGER.info("✓ Registered JustTeams /tc on ServerStarting dispatcher!");
                        } catch (Exception e) {
                            LOGGER.warning("Error getting dispatcher on server start: " + e.getMessage());
                        }
                    }
                    return null;
                }
            );

            FabricTeamCommand.invokeEventRegister(startingEvent, startingListener);
            LOGGER.info("✓ Hooked ServerLifecycleEvents.SERVER_STARTING for /tc!");
        } catch (Throwable t) {
            LOGGER.warning("Could not hook ServerLifecycleEvents for chat: " + t.getMessage());
        }
    }

    public static void registerChatCommands(CommandDispatcher<Object> dispatcher, JustTeamsFabric plugin) {
        if (dispatcher == null) return;
        
        LiteralArgumentBuilder<Object> tcBuilder = LiteralArgumentBuilder.literal("tc")
            .executes(ctx -> toggleTeamChat(ctx.getSource(), plugin))
            .then(RequiredArgumentBuilder.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> sendTeamMessage(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "message"))));

        dispatcher.register(tcBuilder);

        LiteralArgumentBuilder<Object> teammsgBuilder = LiteralArgumentBuilder.literal("teammsg")
            .then(RequiredArgumentBuilder.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> sendTeamMessage(ctx.getSource(), plugin, StringArgumentType.getString(ctx, "message"))));

        dispatcher.register(teammsgBuilder);
        dispatcher.register(LiteralArgumentBuilder.literal("tm").redirect(dispatcher.register(teammsgBuilder)));
    }

    private static void sendFeedback(Object source, String text) {
        FabricTeamCommand.sendFeedback(source, text);
    }

    private static int toggleTeamChat(Object source, JustTeamsFabric plugin) {
        sendFeedback(source, "§6Toggled team chat mode.");
        return 1;
    }

    private static int sendTeamMessage(Object source, JustTeamsFabric plugin, String message) {
        sendFeedback(source, "§b[Team Chat] §f" + message);
        return 1;
    }
}
