package com.pixelclub.teams.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Donor 2.5.5 columns. Templates are used only by isolated test fixtures. */
final class DonorSchema {
    static final Map<String, String> DEFINITIONS;
    static final Map<String, Map<String, String>> COLUMNS;

    static {
        Map<String, String> tables = new LinkedHashMap<>();
        tables.put("teams", "id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(128) NOT NULL UNIQUE, tag VARCHAR(255) NOT NULL, owner_uuid VARCHAR(36) NOT NULL, home_location VARCHAR(255), home_server VARCHAR(255), pvp_enabled BOOLEAN DEFAULT TRUE, is_public BOOLEAN DEFAULT FALSE, glow_enabled BOOLEAN DEFAULT TRUE, color VARCHAR(32), gradient_start VARCHAR(7), gradient_end VARCHAR(7), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, description VARCHAR(64), balance DOUBLE DEFAULT 0, kills INT DEFAULT 0, deaths INT DEFAULT 0, join_fee_enabled BOOLEAN DEFAULT FALSE, join_fee_amount DOUBLE DEFAULT 0, tier INT DEFAULT 1, points BIGINT DEFAULT 0, alias VARCHAR(32), accept_requests BOOLEAN NOT NULL DEFAULT TRUE");
        tables.put("team_members", "player_uuid VARCHAR(36) PRIMARY KEY, team_id INT NOT NULL, role VARCHAR(16) NOT NULL, join_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, can_withdraw BOOLEAN DEFAULT FALSE, can_use_enderchest BOOLEAN DEFAULT TRUE, can_set_home BOOLEAN DEFAULT FALSE, can_use_home BOOLEAN DEFAULT TRUE, can_promote_to_co_owner BOOLEAN DEFAULT FALSE, can_edit_members BOOLEAN DEFAULT FALSE, can_edit_co_owners BOOLEAN DEFAULT FALSE, can_kick_members BOOLEAN DEFAULT FALSE, can_promote_members BOOLEAN DEFAULT FALSE, can_demote_members BOOLEAN DEFAULT FALSE");
        tables.put("team_enderchest", "team_id INT PRIMARY KEY, inventory_data MEDIUMTEXT");
        tables.put("team_warps", "id INT AUTO_INCREMENT PRIMARY KEY, team_id INT NOT NULL, warp_name VARCHAR(32) NOT NULL, location VARCHAR(255) NOT NULL, server_name VARCHAR(64) NOT NULL, password VARCHAR(64), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        tables.put("team_homes", "team_id INT PRIMARY KEY, location VARCHAR(255) NOT NULL, server_name VARCHAR(64) NOT NULL");
        tables.put("team_allies", "id INT AUTO_INCREMENT PRIMARY KEY, team_id_1 INT NOT NULL, team_id_2 INT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        tables.put("team_ally_requests", "id INT AUTO_INCREMENT PRIMARY KEY, sender_team_id INT NOT NULL, target_team_id INT NOT NULL, requester_uuid VARCHAR(36) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        tables.put("team_blacklist", "id INT AUTO_INCREMENT PRIMARY KEY, team_id INT NOT NULL, player_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(16) NOT NULL, reason TEXT, blacklisted_by_uuid VARCHAR(36) NOT NULL, blacklisted_by_name VARCHAR(16) NOT NULL, blacklisted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        tables.put("team_custom_data", "team_id INT NOT NULL, data_key VARCHAR(255) NOT NULL, data_value TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        tables.put("team_quest_progress", "team_id INT NOT NULL, quest_id VARCHAR(64) NOT NULL, progress BIGINT DEFAULT 0, started_at BIGINT DEFAULT 0, completed BOOLEAN DEFAULT FALSE, claimed BOOLEAN DEFAULT FALSE");
        tables.put("team_rename_cooldowns", "team_id INT PRIMARY KEY, last_rename TIMESTAMP NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        tables.put("team_invites", "id INT AUTO_INCREMENT PRIMARY KEY, team_id INT NOT NULL, player_uuid VARCHAR(36) NOT NULL, inviter_uuid VARCHAR(36) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        tables.put("join_requests", "id INT AUTO_INCREMENT PRIMARY KEY, team_id INT NOT NULL, player_uuid VARCHAR(36) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        tables.put("player_cache", "player_uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(16) NOT NULL, last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        tables.put("player_sessions", "player_uuid VARCHAR(36) PRIMARY KEY, server_name VARCHAR(255) NOT NULL, last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        tables.put("servers", "server_name VARCHAR(64) PRIMARY KEY, last_heartbeat TIMESTAMP NOT NULL");
        tables.put("server_aliases", "server_name VARCHAR(255) PRIMARY KEY, alias VARCHAR(64) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        tables.put("pending_teleports", "player_uuid VARCHAR(36) PRIMARY KEY, destination_server VARCHAR(255) NOT NULL, destination_location VARCHAR(255) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        tables.put("team_locks", "team_id INT PRIMARY KEY, server_identifier VARCHAR(255) NOT NULL, lock_time TIMESTAMP NOT NULL");
        tables.put("ender_chest_locks", "team_id INT PRIMARY KEY, server_identifier VARCHAR(255) NOT NULL, lock_time TIMESTAMP NOT NULL");
        tables.put("cross_server_messages", "id INT AUTO_INCREMENT PRIMARY KEY, team_id INT NOT NULL, player_uuid VARCHAR(36) NOT NULL, message TEXT NOT NULL, server_name VARCHAR(64) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        tables.put("cross_server_updates", "id INT AUTO_INCREMENT PRIMARY KEY, team_id INT NOT NULL, update_type VARCHAR(50) NOT NULL, player_uuid VARCHAR(36) NOT NULL, server_name VARCHAR(64) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        DEFINITIONS = Collections.unmodifiableMap(tables);
        Map<String, Map<String, String>> columns = new LinkedHashMap<>();
        tables.forEach((table, definition) -> {
            Map<String, String> names = new LinkedHashMap<>();
            for (String part : definition.split(", ")) {
                String[] tokens = part.split(" ");
                names.put(tokens[0], tokens[1]);
            }
            columns.put(table, Collections.unmodifiableMap(names));
        });
        COLUMNS = Collections.unmodifiableMap(columns);
    }

    private DonorSchema() {}
}
