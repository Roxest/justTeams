package eu.kotori.justTeams.util;

import eu.kotori.justTeams.JustTeamsFabric;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.level.ServerPlayer;

public class FabricChatInputManager {

    private static final Logger LOGGER = Logger.getLogger(FabricChatInputManager.class.getName());
    private final JustTeamsFabric plugin;
    private final Map<UUID, Consumer<String>> pendingInputs = new ConcurrentHashMap<>();

    public FabricChatInputManager(JustTeamsFabric plugin) {
        this.plugin = plugin;
        this.registerListener();
    }

    private void registerListener() {
        try {
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, player, params) -> {
                if (player == null) return true;
                UUID uuid = player.getUUID();
                Consumer<String> callback = pendingInputs.remove(uuid);
                if (callback != null) {
                    String text = message.signedContent();
                    if (text == null || text.isEmpty()) {
                        text = message.decoratedContent().getString();
                    }
                    final String inputText = text.trim();
                    player.getServer().execute(() -> {
                        try {
                            callback.accept(inputText);
                        } catch (Exception e) {
                            LOGGER.severe("Error handling chat input: " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                    return false; // Intercept & don't broadcast to public chat!
                }
                return true;
            });
            LOGGER.info("✓ Hooked Fabric ServerMessageEvents.ALLOW_CHAT_MESSAGE for in-menu chat input!");
        } catch (Throwable t) {
            LOGGER.warning("Could not hook ServerMessageEvents: " + t.getMessage());
        }
    }

    public void awaitInput(ServerPlayer player, Consumer<String> onInput) {
        if (player == null) return;
        pendingInputs.put(player.getUUID(), onInput);
    }

    public boolean hasPendingInput(ServerPlayer player) {
        if (player == null) return false;
        return pendingInputs.containsKey(player.getUUID());
    }

    public void cancelInput(ServerPlayer player) {
        if (player == null) return;
        pendingInputs.remove(player.getUUID());
    }
}
