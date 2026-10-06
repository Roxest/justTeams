package com.pixelclub.teams;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringUtil;
import net.minecraft.world.item.component.ResolvableProfile;
import org.samo_lego.fabrictailor.api.FabricTailorSkins;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * pxc.8: member heads carry the member's real skin. The server is offline-mode, so a head with only name + UUID makes
 * the client ask Mojang by an offline UUID and draw a default skin ("all steve", 10-06), and offline members had no
 * profile at all. The texture comes from the online profile when it has one, else from FabricTailor (its MySQL store
 * first), cached per name; a miss starts a lookup and the next open shows the skin. Never throws.
 */
final class TeamHeads {

    private static final long REFRESH_MS = 10 * 60_000L;
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private record Entry(Property skin, long at, boolean pending) {
    }

    private TeamHeads() {
    }

    static ResolvableProfile profile(MinecraftServer server, UUID id, String name) {
        // A profile name over 16 chars or with odd characters fails the whole menu packet and kicks the viewer.
        String safe = name != null && StringUtil.isValidPlayerName(name) ? name : "";
        PropertyMap props = new PropertyMap();
        try {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            Property live = online == null ? null : online.getGameProfile().getProperties().get("textures").stream().findFirst().orElse(null);
            Property skin = live != null ? live : cached(server, safe);
            if (skin != null) props.put("textures", skin);
        } catch (Throwable ignored) {
        }
        return new ResolvableProfile(Optional.of(safe), Optional.ofNullable(id), props);
    }

    private static Property cached(MinecraftServer server, String name) {
        if (name.isEmpty()) return null;
        String key = name.toLowerCase(Locale.ROOT);
        Entry e = CACHE.get(key);
        if (e == null || (!e.pending() && System.currentTimeMillis() - e.at() > REFRESH_MS)) lookup(server, key, name, e);
        return e == null ? null : e.skin();
    }

    private static void lookup(MinecraftServer server, String key, String name, Entry previous) {
        if (!FabricLoader.getInstance().isModLoaded("fabrictailor")) return;
        Property stale = previous == null ? null : previous.skin();
        CACHE.put(key, new Entry(stale, System.currentTimeMillis(), true));
        try {
            FabricTailorSkins.findByName(server, name).whenComplete((found, err) -> CACHE.put(key,
                    new Entry(err == null && found != null && found.isPresent() ? found.get() : stale, System.currentTimeMillis(), false)));
        } catch (Throwable t) {
            CACHE.put(key, new Entry(stale, System.currentTimeMillis(), false));
        }
    }
}
