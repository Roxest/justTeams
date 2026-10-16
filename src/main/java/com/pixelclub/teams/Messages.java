package com.pixelclub.teams;

import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Port refusal/success codes -> the donor's messages.yml keys. Without this players read the bare code
 * ("TELEPORT_WARMUP", "NO_TEAM") where the Bukkit plugin said "Teleporting in 5 seconds... Don't move!" (PRD 10-04).
 * A code with no entry falls back to its lower-case name, then to the code itself.
 */
final class Messages {
    private Messages(){}
    private static final Map<String,String> KEYS=Map.ofEntries(
        Map.entry("NO_TEAM","player_not_in_team"),
        Map.entry("DENIED","no_permission"),
        Map.entry("MEMBER_FLAG_DENIED","no_permission"),
        Map.entry("NO_WARP","warp_not_found"),
        Map.entry("INVALID_WARP","invalid_warp_name"),
        Map.entry("WARP_PASSWORD_REQUIRED","warp_password_protected"),
        Map.entry("WARP_PASSWORD_INCORRECT","warp_incorrect_password"),
        Map.entry("TELEPORT_COOLDOWN","teleport_cooldown"),
        Map.entry("TELEPORT_WARMUP","teleport_warmup"),
        Map.entry("TELEPORT_CANCELLED","teleport_moved"),
        Map.entry("TELEPORTED_HOME","team_home_teleported"),
        Map.entry("TELEPORTED_WARP","warp_teleported"),
        Map.entry("WORLD_DENIED","feature_disabled_in_world"),
        Map.entry("NO_HOME","home_not_set"),
        Map.entry("HOME_UPDATED","home_set"),
        Map.entry("HOME_DELETED","home_deleted"),
        Map.entry("WARP_UPDATED","warp_set"),
        Map.entry("WARP_DELETED","warp_deleted"),
        Map.entry("REMOTE_SERVER_UNAVAILABLE","proxy_not_enabled"),
        Map.entry("FEATURE_DISABLED","feature_disabled"),
        Map.entry("UNKNOWN_TEAM","team_not_found"),
        Map.entry("ALREADY_IN_TEAM","already_in_team"),
        Map.entry("TEAM_FULL","team_is_full"),
        Map.entry("NOT_OWNER","must_be_owner"),
        Map.entry("OWNER_REQUIRED","must_be_owner"),
        Map.entry("ELEVATED_REQUIRED","must_be_owner_or_co_owner"),
        Map.entry("UNKNOWN_TEAM_ACTION","unknown_command"),
        Map.entry("PLAYER_OFFLINE","player_offline"),
        Map.entry("ALREADY_REQUESTED","already_requested_to_join"),
        Map.entry("BLACKLISTED","player_is_blacklisted"),
        Map.entry("DESCRIPTION_TOO_LONG","description_too_long"),
        Map.entry("INVALID_COLOR","invalid_color"),
        Map.entry("MAX_TIER","team_tier_max_reached"),
        Map.entry("TEAM_UPGRADES_DISABLED","team_upgrades_disabled"),
        Map.entry("UPGRADES_DISABLED","team_upgrades_disabled"),
        Map.entry("PVP_COOLDOWN","pvp_toggle_cooldown"),
        // X4 replay 10-16: the second viewer saw the raw code. Not enderchest_locked_by_proxy ("on another server"):
        // a player must not learn the network is split.
        Map.entry("CHEST_IN_USE","enderchest_locked"));

    /** Codes this port added (X4) that no donor messages.yml has: shown in Thai unless messages.yml defines the code. */
    private static final Map<String,String> FALLBACK=Map.of(
        "REMOTE_TRAVEL","&eกำลังพาไปเซิร์ฟ <server>...",
        "REMOTE_TRAVEL_REFUSED","&cไปเซิร์ฟ <server> ไม่ได้ตอนนี้: <reason>",
        "REMOTE_WARP_LOCKED","&cวาร์ป <warp> อยู่อีกเซิร์ฟและตั้งรหัสไว้ — ใช้ได้เฉพาะในเซิร์ฟนั้น");

    static String fallback(String code){return FALLBACK.get(code);}

    /** messages.yml key for code, or null when the donor has none; MISSING_ARGUMENT prefers usage_&lt;action&gt;. */
    static String key(String code,Map<String,String> values,Predicate<String> exists){
        if(code.equals("MISSING_ARGUMENT")&&values.containsKey("action")){String usage="usage_"+values.get("action");if(exists.test(usage))return usage;}
        String mapped=KEYS.get(code);if(mapped!=null&&exists.test(mapped))return mapped;
        String plain=code.toLowerCase(Locale.ROOT);return exists.test(plain)?plain:null;
    }
}
