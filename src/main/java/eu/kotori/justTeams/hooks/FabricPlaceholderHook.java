package eu.kotori.justTeams.hooks;

import eu.kotori.justTeams.storage.FabricStorageManager.TeamRecord;
import eu.kotori.justTeams.team.FabricTeamManager;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class FabricPlaceholderHook {

    private static final Logger LOGGER = Logger.getLogger(FabricPlaceholderHook.class.getName());
    private static boolean registered = false;

    public static void register(FabricTeamManager teamManager) {
        if (teamManager == null) {
            return;
        }

        // 1. Hook Pixelclub pxc-placeholders (Native PAPI on Fabric)
        registerPxcPlaceholders(teamManager);

        // 2. Hook Patbox Text Placeholder API (eu.pb4.placeholders.api)
        registerPb4Placeholders(teamManager);
    }

    private static void registerPxcPlaceholders(FabricTeamManager teamManager) {
        try {
            Class<?> pxcClass = Class.forName("com.pixelclub.placeholders.api.PxcPlaceholders");
            Class<?> legacyExpansionClass = Class.forName("com.pixelclub.placeholders.api.LegacyExpansion");

            Object pxcProxy = Proxy.newProxyInstance(
                legacyExpansionClass.getClassLoader(),
                new Class<?>[]{legacyExpansionClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("onRequest") && args != null && args.length >= 2) {
                        Object subject = args[0];
                        String params = args[1] != null ? String.valueOf(args[1]) : "";
                        UUID uuid = null;
                        if (subject != null) {
                            try {
                                Method uuidMethod = subject.getClass().getMethod("uuid");
                                uuid = (UUID) uuidMethod.invoke(subject);
                            } catch (Exception ignored) {}
                        }
                        return onRequest(uuid, params, teamManager);
                    }
                    return null;
                }
            );

            Method regMethod = pxcClass.getMethod("register", String.class, String.class, legacyExpansionClass);
            regMethod.invoke(null, "justteams", "justteams", pxcProxy);
            registered = true;
            LOGGER.info("★ Successfully hooked into Pixelclub pxc-placeholders! (%justteams_*% fully active)");
        } catch (ClassNotFoundException e) {
            LOGGER.info("ℹ pxc-placeholders not present on server. Falling back to pb4 / standalone.");
        } catch (Exception e) {
            LOGGER.warning("Could not register with pxc-placeholders: " + e.getMessage());
        }
    }

    private static void registerPb4Placeholders(FabricTeamManager teamManager) {
        try {
            Class<?> placeholdersClass = Class.forName("eu.pb4.placeholders.api.Placeholders");
            Class<?> placeholderHandlerClass = Class.forName("eu.pb4.placeholders.api.PlaceholderHandler");
            Class<?> placeholderResultClass = Class.forName("eu.pb4.placeholders.api.PlaceholderResult");
            Class<?> identifierClass = Class.forName("net.minecraft.util.Identifier");
            Class<?> textClass = Class.forName("net.minecraft.text.Text");

            Method ofIdentifierMethod = null;
            for (Method m : identifierClass.getMethods()) {
                if (m.getName().equals("of") && m.getParameterCount() == 2) {
                    ofIdentifierMethod = m;
                    break;
                }
            }
            if (ofIdentifierMethod == null) {
                ofIdentifierMethod = identifierClass.getMethod("tryParse", String.class);
            }

            Method valueMethod = placeholderResultClass.getMethod("value", textClass);
            Method literalTextMethod = textClass.getMethod("literal", String.class);

            Object handlerProxy = Proxy.newProxyInstance(
                placeholderHandlerClass.getClassLoader(),
                new Class<?>[]{placeholderHandlerClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("onPlaceholderRequest") || method.getName().equals("parse")) {
                        Object ctx = args[0];
                        String arg = args.length >= 2 && args[1] != null ? String.valueOf(args[1]) : "";
                        UUID playerUuid = extractPlayerUuid(ctx);
                        String result = onRequest(playerUuid, arg, teamManager);
                        Object textObj = literalTextMethod.invoke(null, result);
                        return valueMethod.invoke(null, textObj);
                    }
                    return null;
                }
            );

            // Register identifier "justteams"
            Method registerMethod = placeholdersClass.getMethod("register", identifierClass, placeholderHandlerClass);
            Object rootId = ofIdentifierMethod.getParameterCount() == 2
                ? ofIdentifierMethod.invoke(null, "justteams", "data")
                : ofIdentifierMethod.invoke(null, "justteams:data");
            registerMethod.invoke(null, rootId, handlerProxy);

            registered = true;
            LOGGER.info("★ Registered with pb4 Placeholder API (%justteams:*% and %justteams_*% active)!");
        } catch (ClassNotFoundException e) {
            LOGGER.info("ℹ pb4 Placeholder API not present on server.");
        } catch (Exception e) {
            LOGGER.warning("Could not register with pb4 Placeholder API: " + e.getMessage());
        }
    }

    private static UUID extractPlayerUuid(Object ctx) {
        if (ctx == null) return null;
        try {
            Method getPlayerMethod = ctx.getClass().getMethod("player");
            Object player = getPlayerMethod.invoke(ctx);
            if (player != null) {
                Method getUuidMethod = player.getClass().getMethod("getUuid");
                return (UUID) getUuidMethod.invoke(player);
            }
        } catch (Exception ignored) {}
        try {
            Method getPlayerMethod = ctx.getClass().getMethod("getPlayer");
            Object player = getPlayerMethod.invoke(ctx);
            if (player != null) {
                Method getUuidMethod = player.getClass().getMethod("getUuid");
                return (UUID) getUuidMethod.invoke(player);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static String onRequest(UUID playerUuid, String params, FabricTeamManager teamManager) {
        if (teamManager == null || params == null) {
            return "";
        }

        if (params.startsWith("top_") || params.startsWith("leaderboard_")) {
            return handleTopPlaceholder(params, teamManager);
        }

        if (playerUuid == null) {
            return "";
        }

        TeamRecord team = teamManager.getPlayerTeam(playerUuid);
        if (team == null) {
            if ("in_team".equalsIgnoreCase(params)) return "false";
            if ("role".equalsIgnoreCase(params)) return "None";
            if ("balance".equalsIgnoreCase(params) || "kills".equalsIgnoreCase(params) || "deaths".equalsIgnoreCase(params) || "points".equalsIgnoreCase(params) || "bank".equalsIgnoreCase(params)) {
                return "0";
            }
            if ("has_home".equalsIgnoreCase(params)) return "false";
            return "";
        }

        switch (params.toLowerCase()) {
            case "in_team":
                return "true";
            case "name":
                return team.name != null ? team.name : "";
            case "tag":
            case "tag_nocolor":
                return team.tag != null ? team.tag : "";
            case "colored_tag":
            case "tag_colored":
                return team.tag != null ? "§6[" + team.tag + "§6]" : "";
            case "role":
                return playerUuid.equals(team.ownerUuid) ? "OWNER" : "MEMBER";
            case "balance":
            case "bank":
                return String.format("%.2f", team.balance);
            case "balance_formatted":
            case "bank_formatted":
                return "$" + String.format("%.2f", team.balance);
            case "kills":
                return String.valueOf(team.kills);
            case "deaths":
                return String.valueOf(team.deaths);
            case "kdr":
                double kdr = team.deaths == 0 ? team.kills : (double) team.kills / team.deaths;
                return String.format("%.2f", kdr);
            case "tier":
                return String.valueOf(team.tier);
            case "points":
                return String.valueOf(team.points);
            case "pvp":
            case "friendly_fire":
                return team.pvpEnabled ? "Enabled" : "Disabled";
            case "is_public":
                return String.valueOf(team.isPublic);
            case "has_home":
                return String.valueOf(team.homeLocation != null && !team.homeLocation.isEmpty());
            case "owner":
                return team.ownerUuid != null ? team.ownerUuid.toString() : "Unknown";
            default:
                return "";
        }
    }

    private static String handleTopPlaceholder(String params, FabricTeamManager teamManager) {
        String[] parts = params.split("_");
        if (parts.length >= 4) {
            try {
                String category = parts[1]; // kills, balance, points
                int pos = Integer.parseInt(parts[2]);
                String type = parts[3]; // name, amount, tag

                List<TeamRecord> tops = teamManager.getTopTeams(Math.max(pos, 10));
                if (pos >= 1 && pos <= tops.size()) {
                    TeamRecord t = tops.get(pos - 1);
                    if ("name".equalsIgnoreCase(type)) return t.name;
                    if ("tag".equalsIgnoreCase(type)) return t.tag;
                    if ("amount".equalsIgnoreCase(type) || "value".equalsIgnoreCase(type)) {
                        if ("balance".equalsIgnoreCase(category)) return String.format("%.2f", t.balance);
                        if ("kills".equalsIgnoreCase(category)) return String.valueOf(t.kills);
                        return String.valueOf(t.points);
                    }
                }
            } catch (Exception ignored) {}
        }
        return "---";
    }

    public static boolean isRegistered() {
        return registered;
    }
}
