package com.pixelclub.teams.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** JDBC-only storage. Each mutation holds the store monitor and commits one transaction. */
public final class TeamStore {
    private static final String OPERATIONS = "pxc_teams_operations";
    private static final String CHESTS = "pxc_teams_chests";
    private static final Set<String> FLAGS = Set.of("can_withdraw", "can_use_enderchest", "can_set_home", "can_use_home", "can_promote_to_co_owner", "can_edit_members", "can_edit_co_owners", "can_kick_members", "can_promote_members", "can_demote_members");
    private static final Set<String> TEAM_UPDATES = Set.of("name", "tag", "home_location", "home_server", "pvp_enabled", "is_public", "glow_enabled", "color", "gradient_start", "gradient_end", "description", "kills", "deaths", "points", "join_fee_enabled", "join_fee_amount", "alias", "accept_requests");
    private static final Set<String> GENERIC_WRITES = Set.of("team_allies", "team_ally_requests", "team_blacklist", "team_custom_data", "team_quest_progress", "team_homes", "team_rename_cooldowns", "join_requests", "player_cache", "server_aliases");
    private static final Set<String> TERMINAL_PHASES = Set.of("DONE", "COMPLETED", "COMPENSATED", "FAILED", "REJECTED", "CANCELLED", "CONSUMED", "INVITED", "IDENTITY");
    private static final Set<String> FINANCIAL_KINDS = Set.of("DEPOSIT", "WITHDRAW", "FEATURE_COST", "BANK");
    private static final Set<String> FINANCIAL_TERMINAL_PHASES = Set.of("DONE", "COMPLETED", "COMPENSATED", "FAILED", "REJECTED", "CANCELLED");
    private final ConnectionFactory factory;
    private final String prefix;

    public TeamStore(ConnectionFactory factory, String prefix) {
        this.factory = Objects.requireNonNull(factory);
        if (prefix == null || !prefix.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Unsafe table prefix");
        this.prefix = prefix;
    }

    public record Operation(String opid, int teamId, UUID player, String kind, double amount,
                            String phase, String payload, long createdAt, long updatedAt) {
        public boolean effectApplied() { return Json.booleanValue(payload, "effectApplied"); }
    }
    public record BankResult(boolean applied, boolean duplicate, double balance, Operation operation, String reason) {}
    public record Chest(int teamId, int formatVersion, String inventoryJson, String originalSha256,
                        long generation, long updatedAt) {}
    public record ChestSeed(int teamId, int formatVersion, String inventoryJson, String originalSha256) {}
    public static final class CreateConflict extends SQLException {public CreateConflict(){super("Team creation conflict");}}

    public Map<String, String> requiredSchema() {
        Map<String, String> result = new LinkedHashMap<>();
        DonorSchema.DEFINITIONS.forEach((suffix, definition) -> result.put(prefix + suffix, definition));
        return Collections.unmodifiableMap(result);
    }

    /** Read-only validation of all 22 donor tables; no CREATE/ALTER fallback is permitted. */
    public void validateSchema() throws SQLException {
        try (Connection connection = factory.open()) {
            for (var table : DonorSchema.COLUMNS.entrySet()) {
                try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table(table.getKey()) + " WHERE 1=0"); ResultSet result = statement.executeQuery()) {
                    ResultSetMetaData metadata = result.getMetaData();
                    Map<String, Integer> found = new LinkedHashMap<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) found.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
                    for (var column : table.getValue().entrySet()) {
                        Integer index = found.get(column.getKey());
                        if (index == null || !compatible(column.getValue(), metadata.getColumnType(index)) || !wideEnough(column.getValue(), metadata.getPrecision(index))) {
                            throw new SQLException("Donor schema mismatch: " + prefix + table.getKey() + "." + column.getKey() + " expected " + column.getValue());
                        }
                    }
                }
            }
        }
    }

    /** Explicit provisioning stage only. Retains journal audit rows after a team is deleted. */
    public synchronized void provisionAdditiveTables() throws SQLException {
        try (Connection connection = factory.open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + OPERATIONS + " (opid VARCHAR(192) PRIMARY KEY, team_id INT NOT NULL, player_uuid VARCHAR(36), kind VARCHAR(64) NOT NULL, amount DOUBLE NOT NULL, phase VARCHAR(32) NOT NULL, payload LONGTEXT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS " + CHESTS + " (team_id INT PRIMARY KEY, format_version INT NOT NULL, inventory_json LONGTEXT NOT NULL, original_sha256 VARCHAR(64), generation BIGINT NOT NULL, updated_at BIGINT NOT NULL, FOREIGN KEY(team_id) REFERENCES " + table("teams") + "(id) ON DELETE CASCADE)");
        }
    }

    /** Creates donor templates only in H2 in-memory fixtures, never a production/import database. */
    public synchronized void createFixtureSchema() throws SQLException {
        try (Connection connection = factory.open(); Statement statement = connection.createStatement()) {
            if (!connection.getMetaData().getURL().startsWith("jdbc:h2:mem:")) throw new SQLException("Fixture schema requires H2 in-memory database");
            for (var definition : DonorSchema.DEFINITIONS.entrySet()) {
                String suffix = definition.getKey();
                String constraints = "";
                if (!suffix.equals("teams") && DonorSchema.COLUMNS.get(suffix).containsKey("team_id")) {
                    constraints += ", FOREIGN KEY(team_id) REFERENCES " + table("teams") + "(id) ON DELETE CASCADE";
                }
                if (suffix.equals("team_allies")) constraints += ", UNIQUE(team_id_1,team_id_2), FOREIGN KEY(team_id_1) REFERENCES " + table("teams") + "(id) ON DELETE CASCADE, FOREIGN KEY(team_id_2) REFERENCES " + table("teams") + "(id) ON DELETE CASCADE";
                if (suffix.equals("team_ally_requests")) constraints += ", UNIQUE(sender_team_id,target_team_id), FOREIGN KEY(sender_team_id) REFERENCES " + table("teams") + "(id) ON DELETE CASCADE, FOREIGN KEY(target_team_id) REFERENCES " + table("teams") + "(id) ON DELETE CASCADE";
                if (suffix.equals("team_warps")) constraints += ", UNIQUE(team_id,warp_name)";
                if (Set.of("team_blacklist", "team_invites", "join_requests").contains(suffix)) constraints += ", UNIQUE(team_id,player_uuid)";
                if (suffix.equals("team_custom_data")) constraints += ", PRIMARY KEY(team_id,data_key)";
                if (suffix.equals("team_quest_progress")) constraints += ", PRIMARY KEY(team_id,quest_id)";
                statement.execute("CREATE TABLE " + table(suffix) + " (" + definition.getValue() + constraints + ")");
            }
        }
        provisionAdditiveTables();
    }

    public Optional<Team> team(int teamId) throws SQLException {
        try (Connection connection = factory.open()) { return team(connection, teamId, false); }
    }
    public Optional<Team> teamOf(UUID player) throws SQLException {
        try (Connection connection = factory.open()) {
            return first(connection, "SELECT t.* FROM " + table("teams") + " t JOIN " + table("team_members") + " m ON m.team_id=t.id WHERE m.player_uuid=?", player).map(Team::new);
        }
    }
    public Optional<Team> teamNamed(String name) throws SQLException {
        try (Connection connection = factory.open()) {
            return first(connection, "SELECT * FROM " + table("teams") + " WHERE LOWER(name)=LOWER(?)", name).map(Team::new);
        }
    }
    public Optional<Member> member(UUID player) throws SQLException {
        try (Connection connection = factory.open()) {
            return first(connection, "SELECT m.*,c.player_name FROM " + table("team_members") + " m LEFT JOIN " + table("player_cache") + " c ON c.player_uuid=m.player_uuid WHERE m.player_uuid=?", player).map(Member::new);
        }
    }
    public List<Member> members(int teamId) throws SQLException {
        try (Connection connection = factory.open()) {
            return query(connection, "SELECT m.*,c.player_name FROM " + table("team_members") + " m LEFT JOIN " + table("player_cache") + " c ON c.player_uuid=m.player_uuid WHERE m.team_id=? ORDER BY m.join_date,m.player_uuid", teamId).stream().map(Member::new).toList();
        }
    }
    public List<Team> top(String category, int limit) throws SQLException {
        if (!Set.of("balance", "kills", "deaths", "points", "tier", "members").contains(category) || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid ranking category/limit");
        try (Connection connection = factory.open()) {
            if (category.equals("members")) {
                return query(connection, "SELECT t.*,(SELECT COUNT(*) FROM " + table("team_members") + " m WHERE m.team_id=t.id) AS member_count FROM " + table("teams") + " t ORDER BY member_count DESC,t.id ASC LIMIT ?", limit).stream().map(Team::new).toList();
            }
            return query(connection, "SELECT * FROM " + table("teams") + " ORDER BY " + quote(category) + " DESC,id ASC LIMIT ?", limit).stream().map(Team::new).toList();
        }
    }
    public List<Map<String, Object>> rows(String suffix, String whereColumn, Object value) throws SQLException {
        checkSuffix(suffix);
        if (whereColumn != null) checkColumn(suffix, whereColumn);
        try (Connection connection = factory.open()) {
            return whereColumn == null ? query(connection, "SELECT * FROM " + table(suffix)) : query(connection, "SELECT * FROM " + table(suffix) + " WHERE " + quote(whereColumn) + "=?", value);
        }
    }

    public synchronized Team create(String name, String tag, UUID owner, String ownerName, String serverName) throws SQLException {
        return create(name, tag, owner, ownerName, serverName, null);
    }
    public synchronized Team create(String name, String tag, UUID owner, String ownerName, String serverName, String color) throws SQLException {
        requireText(name, 128); requireText(tag, 255); requireText(ownerName, 16); requireText(serverName, 255);
        if (color != null && color.length() > 32) throw new IllegalArgumentException("Team color is too long");
        return transaction(connection -> {
            if (member(connection, owner, true).isPresent()) throw new CreateConflict();
            if (identityExists(connection, "name", name, 0) || identityExists(connection, "tag", tag, 0)) throw new CreateConflict();
            int id;
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + table("teams") + " (name,tag,owner_uuid,home_server,color) VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, name, tag, owner, serverName, color);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Missing generated team id");
                    id = keys.getInt(1);
                }
            }
            claimIdentity(connection, "tag", tag, id, owner);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("player_uuid", owner); values.put("team_id", id); values.put("role", "OWNER");
            FLAGS.forEach(flag -> values.put(flag, true));
            insert(connection, "team_members", values);
            cachePlayer(connection, owner, ownerName);
            return team(connection, id, false).orElseThrow(() -> new SQLException("Created team missing"));
        });
    }

    public synchronized boolean invite(int teamId, UUID inviter, UUID target, long expiresEpochMillis) throws SQLException {
        if (expiresEpochMillis <= System.currentTimeMillis()) return false;
        return transaction(connection -> {
            if (team(connection, teamId, true).isEmpty()) return false;
            Optional<Member> source = member(connection, inviter, true);
            if (source.isEmpty() || source.get().teamId() != teamId || member(connection, target, true).isPresent() || blacklisted(connection, teamId, target)) return false;
            if (first(connection, "SELECT id FROM " + table("team_invites") + " WHERE team_id=? AND player_uuid=?", teamId, target).isPresent()) {
                execute(connection, "UPDATE " + table("team_invites") + " SET inviter_uuid=?,created_at=CURRENT_TIMESTAMP WHERE team_id=? AND player_uuid=?", inviter, teamId, target);
            } else insert(connection, "team_invites", Map.of("team_id", teamId, "player_uuid", target, "inviter_uuid", inviter));
            String opid = inviteId(teamId, target);
            String payload = Json.put("{}", "expiresEpochMillis", Long.toString(expiresEpochMillis));
            Optional<Operation> existing = operation(connection, opid, true);
            if (existing.isEmpty()) beginOperation(connection, opid, teamId, target, "INVITE", 0, "INVITED", payload);
            else writeOperation(connection, existing.get(), "INVITED", payload);
            return true;
        });
    }

    public synchronized boolean accept(UUID target, int teamId, int maxMembers) throws SQLException {
        // Donor SQL invitations were existence-only, independent of the local five-minute cache.
        return accept(target, teamId, maxMembers, -1);
    }
    public synchronized boolean accept(UUID target, int teamId, int maxMembers, long legacyInviteLifetimeMillis) throws SQLException {
        if (legacyInviteLifetimeMillis < -1) throw new IllegalArgumentException("Invalid legacy invite lifetime");
        if (maxMembers < 1) return false;
        return transaction(connection -> {
            if (team(connection, teamId, true).isEmpty() || member(connection, target, true).isPresent() || blacklisted(connection, teamId, target)) return false;
            Optional<Map<String, Object>> donor = first(connection, "SELECT * FROM " + table("team_invites") + " WHERE team_id=? AND player_uuid=? FOR UPDATE", teamId, target);
            if (donor.isEmpty()) return false;
            Optional<Operation> invitation = operation(connection, inviteId(teamId, target), true);
            String legacyPayload = null;
            if (invitation.isPresent()) {
                Operation existing = invitation.get();
                checkOperationIdentity(existing, teamId, target, 0);
                if (!existing.kind().equals("INVITE")) throw new SQLException("Invite operation kind mismatch");
                if (!existing.phase().equals("INVITED") || Json.longValue(existing.payload(), "expiresEpochMillis", 0) <= System.currentTimeMillis()) return false;
            } else {
                Object created = donor.get().get("created_at");
                if (created != null && !(created instanceof Timestamp)) throw new SQLException("Invalid donor invite timestamp type");
                long expires = Long.MAX_VALUE;
                if (legacyInviteLifetimeMillis >= 0) {
                    if (created == null) return false;
                    try { expires = Math.addExact(((Timestamp) created).getTime(), legacyInviteLifetimeMillis); } catch (ArithmeticException overflow) { return false; }
                    if (expires <= System.currentTimeMillis()) return false;
                }
                legacyPayload = Json.put("{}", "legacy", "true");
                legacyPayload = Json.put(legacyPayload, "created_at", created == null ? "null" : Json.string(((Timestamp) created).toInstant().toString()));
                legacyPayload = Json.put(legacyPayload, "expiresEpochMillis", Long.toString(expires));
            }
            if (count(connection, "team_members", "team_id", teamId) >= maxMembers) return false;
            if (invitation.isEmpty()) invitation = Optional.of(beginOperation(connection, inviteId(teamId, target), teamId, target, "INVITE", 0, "INVITED", legacyPayload));
            insert(connection, "team_members", Map.of("player_uuid", target, "team_id", teamId, "role", "MEMBER"));
            execute(connection, "DELETE FROM " + table("team_invites") + " WHERE player_uuid=?", target);
            execute(connection, "DELETE FROM " + table("join_requests") + " WHERE player_uuid=?", target);
            writeOperation(connection, invitation.get(), "CONSUMED", invitation.get().payload());
            return true;
        });
    }

    public synchronized boolean joinPublic(UUID target, int teamId, int maxMembers) throws SQLException {
        if (maxMembers < 1) return false;
        return transaction(connection -> {
            Optional<Team> team = team(connection, teamId, true);
            if (team.isEmpty() || !team.get().isPublic() || member(connection, target, true).isPresent() || blacklisted(connection, teamId, target) || count(connection, "team_members", "team_id", teamId) >= maxMembers) return false;
            joinMember(connection, teamId, target);
            return true;
        });
    }

    public synchronized boolean acceptRequest(int teamId, UUID actor, UUID target, int maxMembers) throws SQLException {
        if (maxMembers < 1) return false;
        return transaction(connection -> {
            if (team(connection, teamId, true).isEmpty()) return false;
            Optional<Member> approver = member(connection, actor, true);
            if (approver.isEmpty() || approver.get().teamId() != teamId || !Set.of("OWNER", "CO_OWNER").contains(approver.get().role())) return false;
            if (member(connection, target, true).isPresent() || blacklisted(connection, teamId, target) || count(connection, "team_members", "team_id", teamId) >= maxMembers) return false;
            if (first(connection, "SELECT id FROM " + table("join_requests") + " WHERE team_id=? AND player_uuid=? FOR UPDATE", teamId, target).isEmpty()) return false;
            joinMember(connection, teamId, target);
            return true;
        });
    }

    private void joinMember(Connection connection, int teamId, UUID target) throws SQLException {
        insert(connection, "team_members", Map.of("player_uuid", target, "team_id", teamId, "role", "MEMBER"));
        execute(connection, "DELETE FROM " + table("join_requests") + " WHERE player_uuid=?", target);
        execute(connection, "DELETE FROM " + table("team_invites") + " WHERE player_uuid=?", target);
    }

    public synchronized boolean denyInvite(UUID target, int teamId) throws SQLException {
        return transaction(connection -> {
            team(connection, teamId, true);
            int deleted = execute(connection, "DELETE FROM " + table("team_invites") + " WHERE team_id=? AND player_uuid=?", teamId, target);
            Optional<Operation> invitation = operation(connection, inviteId(teamId, target), true);
            if (invitation.isPresent()) {
                if (!invitation.get().kind().equals("INVITE") || invitation.get().teamId() != teamId || !Objects.equals(invitation.get().player(), target)) throw new SQLException("Invite journal identity mismatch");
                writeOperation(connection, invitation.get(), "CANCELLED", null);
            }
            return deleted > 0;
        });
    }

    public synchronized boolean transferOwnership(int teamId, UUID expectedOwner, UUID target) throws SQLException {
        if (Objects.equals(expectedOwner, target)) return false;
        return transaction(connection -> {
            Optional<Team> team = team(connection, teamId, true);
            if (team.isEmpty() || !team.get().owner().equals(expectedOwner)) return false;
            Optional<Member> formerOwner = member(connection, expectedOwner, true);
            Optional<Member> successor = member(connection, target, true);
            if (formerOwner.isEmpty() || successor.isEmpty() || formerOwner.get().teamId() != teamId || successor.get().teamId() != teamId) return false;
            if (isFrozen(connection, teamId, expectedOwner) || isFrozen(connection, teamId, target) || hasUnsettledFinancialOperation(connection, teamId, expectedOwner) || hasUnsettledFinancialOperation(connection, teamId, target)) return false;
            int promoted = execute(connection, "UPDATE " + table("team_members") + " SET role=?,can_withdraw=?,can_use_enderchest=?,can_set_home=?,can_use_home=? WHERE team_id=? AND player_uuid=?", "OWNER", true, true, true, true, teamId, target);
            int demoted = execute(connection, "UPDATE " + table("team_members") + " SET role=?,can_withdraw=?,can_use_enderchest=?,can_set_home=?,can_use_home=? WHERE team_id=? AND player_uuid=?", "MEMBER", false, true, false, true, teamId, expectedOwner);
            int transferred = execute(connection, "UPDATE " + table("teams") + " SET owner_uuid=? WHERE id=? AND owner_uuid=?", target, teamId, expectedOwner);
            if (promoted != 1 || demoted != 1 || transferred != 1) throw new SQLException("Ownership transfer changed unexpected rows");
            return true;
        });
    }

    public synchronized boolean leave(UUID player) throws SQLException {
        return transaction(connection -> {
            Optional<Member> member = member(connection, player, false);
            return member.isPresent() && removeMember(connection, member.get().teamId(), player);
        });
    }
    public boolean kick(int teamId, UUID target) throws SQLException { return removeMember(teamId, target); }
    public synchronized boolean removeMember(int teamId, UUID target) throws SQLException {
        return transaction(connection -> removeMember(connection, teamId, target));
    }
    private boolean removeMember(Connection connection, int teamId, UUID target) throws SQLException {
        Optional<Team> team = team(connection, teamId, true);
        Optional<Member> member = member(connection, target, true);
        if (team.isEmpty() || member.isEmpty() || member.get().teamId() != teamId || team.get().owner().equals(target) || member.get().owner()) return false;
        if (isFrozen(connection, teamId, target) || hasUnsettledFinancialOperation(connection, teamId, target)) return false;
        return execute(connection, "DELETE FROM " + table("team_members") + " WHERE team_id=? AND player_uuid=? AND role<>?", teamId, target, "OWNER") == 1;
    }

    public synchronized boolean setMemberRoleAndFlags(int teamId, UUID target, String role, Map<String, Boolean> flags) throws SQLException {
        if (!Set.of("OWNER", "CO_OWNER", "MEMBER").contains(role)) throw new IllegalArgumentException("Invalid member role");
        checkFlags(flags);
        return transaction(connection -> {
            Optional<Team> team = team(connection, teamId, true);
            Optional<Member> member = member(connection, target, true);
            if (team.isEmpty() || member.isEmpty() || member.get().teamId() != teamId) return false;
            if (team.get().owner().equals(target) != role.equals("OWNER") || member.get().owner() && !role.equals("OWNER")) return false;
            Map<String, Object> values = new LinkedHashMap<>(flags);
            values.put("role", role);
            return update(connection, "team_members", Map.of("team_id", teamId, "player_uuid", target), values) == 1;
        });
    }
    public synchronized boolean setMemberFlags(int teamId, UUID target, Map<String, Boolean> flags) throws SQLException {
        checkFlags(flags);
        return transaction(connection -> {
            if (team(connection, teamId, true).isEmpty()) return false;
            return update(connection, "team_members", Map.of("team_id", teamId, "player_uuid", target), new LinkedHashMap<>(flags)) == 1;
        });
    }

    public synchronized boolean updateTeam(int teamId, UUID expectedOwner, Map<String, Object> values) throws SQLException {
        if (values.isEmpty()) return false;
        if (!TEAM_UPDATES.containsAll(values.keySet())) throw new IllegalArgumentException("Team column is not mutable through updateTeam");
        if (values.containsKey("name")) requireText((String) values.get("name"), 128);
        if (values.containsKey("tag")) requireText((String) values.get("tag"), 255);
        return transaction(connection -> {
            Optional<Team> old = team(connection, teamId, true);
            if (old.isEmpty() || !old.get().owner().equals(expectedOwner)) return false;
            for (String column : List.of("name", "tag")) {
                if (values.containsKey(column) && identityExists(connection, column, values.get(column).toString(), teamId)) return false;
            }
            if (values.containsKey("tag")) {
                claimIdentity(connection, "tag", values.get("tag").toString(), teamId, expectedOwner);
                if (!canonical(old.get().tag()).equals(canonical(values.get("tag").toString()))) execute(connection, "DELETE FROM " + OPERATIONS + " WHERE opid=? AND kind=? AND team_id=?", identityId("tag", old.get().tag()), "IDENTITY", teamId);
            }
            return update(connection, "teams", Map.of("id", teamId, "owner_uuid", expectedOwner), values) == 1;
        });
    }

    public synchronized boolean rename(int teamId, UUID expectedOwner, String name, long cooldownSeconds, long nowMillis) throws SQLException {
        requireText(name, 128);
        if (cooldownSeconds < 0 || cooldownSeconds > Long.MAX_VALUE / 1000 || nowMillis < 0) throw new IllegalArgumentException("Invalid rename time/cooldown");
        long cooldownMillis = cooldownSeconds * 1000;
        return transaction(connection -> {
            Optional<Team> team = team(connection, teamId, true);
            if (team.isEmpty() || !team.get().owner().equals(expectedOwner) || team.get().name().equals(name) || isFrozen(connection, teamId, expectedOwner)) return false;
            Optional<Map<String, Object>> cooldown = first(connection, "SELECT * FROM " + table("team_rename_cooldowns") + " WHERE team_id=? FOR UPDATE", teamId);
            if (cooldown.isPresent()) {
                long previous = ((Timestamp) cooldown.get().get("last_rename")).getTime();
                long eligible;
                try { eligible = Math.addExact(previous, cooldownMillis); } catch (ArithmeticException overflow) { return false; }
                if (nowMillis < eligible) return false;
            }
            if (identityExists(connection, "name", name, teamId)) return false;
            if (execute(connection, "UPDATE " + table("teams") + " SET name=? WHERE id=? AND owner_uuid=?", name, teamId, expectedOwner) != 1) throw new SQLException("Rename changed unexpected team rows");
            if (cooldown.isPresent()) execute(connection, "UPDATE " + table("team_rename_cooldowns") + " SET last_rename=? WHERE team_id=?", new Timestamp(nowMillis), teamId);
            else insert(connection, "team_rename_cooldowns", Map.of("team_id", teamId, "last_rename", new Timestamp(nowMillis)));
            return true;
        });
    }

    public synchronized boolean allyRequest(int sender, int target, UUID actor, int max) throws SQLException {
        if (max < 1) return false;
        return transaction(connection -> {
            if (!lockTeamPair(connection, sender, target) || !elevated(connection, sender, actor) || allied(connection, sender, target) || allyCount(connection, sender) >= max) return false;
            if (allyRequestRow(connection, sender, target).isPresent()) return false;
            return insert(connection, "team_ally_requests", Map.of("sender_team_id", sender, "target_team_id", target, "requester_uuid", actor)) == 1;
        });
    }

    public synchronized boolean allyAccept(int sender, int target, UUID actor, int max) throws SQLException {
        if (max < 1) return false;
        return transaction(connection -> {
            if (!lockTeamPair(connection, sender, target) || !elevated(connection, target, actor) || allied(connection, sender, target) || allyCount(connection, sender) >= max || allyCount(connection, target) >= max) return false;
            if (allyRequestRow(connection, sender, target).isEmpty()) return false;
            int inserted = insert(connection, "team_allies", Map.of("team_id_1", Math.min(sender, target), "team_id_2", Math.max(sender, target)));
            int deleted = execute(connection, "DELETE FROM " + table("team_ally_requests") + " WHERE sender_team_id=? AND target_team_id=?", sender, target);
            if (inserted != 1 || deleted != 1) throw new SQLException("Ally acceptance changed unexpected rows");
            return true;
        });
    }

    public synchronized boolean allyDeny(int sender, int target, UUID actor) throws SQLException {
        return transaction(connection -> lockTeamPair(connection, sender, target) && elevated(connection, target, actor) && execute(connection, "DELETE FROM " + table("team_ally_requests") + " WHERE sender_team_id=? AND target_team_id=?", sender, target) > 0);
    }

    public synchronized boolean allyRemove(int teamId, int other, UUID actor) throws SQLException {
        return transaction(connection -> lockTeamPair(connection, teamId, other) && elevated(connection, teamId, actor) && execute(connection, "DELETE FROM " + table("team_allies") + " WHERE (team_id_1=? AND team_id_2=?) OR (team_id_1=? AND team_id_2=?)", teamId, other, other, teamId) > 0);
    }

    public synchronized boolean blacklist(int teamId, UUID actor, String actorName, UUID target, String targetName, String reason) throws SQLException {
        requireText(actorName, 16); requireText(targetName, 16);
        Objects.requireNonNull(actor); Objects.requireNonNull(target);
        return transaction(connection -> {
            if (team(connection, teamId, true).isEmpty() || !elevated(connection, teamId, actor) || actor.equals(target)) return false;
            Optional<Member> member = member(connection, target, true);
            if ((member.isPresent() && member.get().teamId() == teamId) || blacklisted(connection, teamId, target)) return false;
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("team_id", teamId); values.put("player_uuid", target); values.put("player_name", targetName); values.put("reason", reason); values.put("blacklisted_by_uuid", actor); values.put("blacklisted_by_name", actorName);
            return insert(connection, "team_blacklist", values) == 1;
        });
    }

    public synchronized boolean adjustPoints(int teamId, UUID expectedOwner, long delta) throws SQLException {
        return transaction(connection -> {
            Optional<Team> team = team(connection, teamId, true);
            if (team.isEmpty() || !team.get().owner().equals(expectedOwner)) return false;
            Object points = team.get().field("points");
            long current = points == null ? 0 : ((Number) points).longValue();
            long changed;
            try { changed = Math.addExact(current, delta); } catch (ArithmeticException overflow) { return false; }
            if (changed < 0) return false;
            return execute(connection, "UPDATE " + table("teams") + " SET points=? WHERE id=? AND owner_uuid=? AND COALESCE(points,0)=?", changed, teamId, expectedOwner, current) == 1;
        });
    }

    private boolean lockTeamPair(Connection connection, int first, int second) throws SQLException {
        if (first < 1 || second < 1 || first == second) return false;
        Optional<Team> smaller = team(connection, Math.min(first, second), true);
        Optional<Team> larger = team(connection, Math.max(first, second), true);
        return smaller.isPresent() && larger.isPresent();
    }
    private boolean elevated(Connection connection, int teamId, UUID actor) throws SQLException {
        Optional<Member> member = member(connection, actor, true);
        return member.isPresent() && member.get().teamId() == teamId && Set.of("OWNER", "CO_OWNER").contains(member.get().role());
    }
    private boolean allied(Connection connection, int first, int second) throws SQLException {
        return first(connection, "SELECT id FROM " + table("team_allies") + " WHERE (team_id_1=? AND team_id_2=?) OR (team_id_1=? AND team_id_2=?) FOR UPDATE", first, second, second, first).isPresent();
    }
    private long allyCount(Connection connection, int teamId) throws SQLException {
        return ((Number) first(connection, "SELECT COUNT(*) AS row_count FROM " + table("team_allies") + " WHERE team_id_1=? OR team_id_2=?", teamId, teamId).orElseThrow().get("row_count")).longValue();
    }
    private Optional<Map<String, Object>> allyRequestRow(Connection connection, int sender, int target) throws SQLException {
        return first(connection, "SELECT * FROM " + table("team_ally_requests") + " WHERE sender_team_id=? AND target_team_id=? FOR UPDATE", sender, target);
    }

    public synchronized boolean deleteTeam(int teamId, UUID expectedOwner) throws SQLException {
        return transaction(connection -> {
            Optional<Team> team = team(connection, teamId, true);
            if (team.isEmpty() || !team.get().owner().equals(expectedOwner) || isFrozen(connection, teamId, expectedOwner) || hasUnsettledFinancialOperation(connection, teamId, expectedOwner)) return false;
            // Explicit deletes also cover donor tables whose historical schema has no FK.
            for (var entry : DonorSchema.COLUMNS.entrySet()) {
                String suffix = entry.getKey();
                if (entry.getValue().containsKey("team_id")) execute(connection, "DELETE FROM " + table(suffix) + " WHERE team_id=?", teamId);
            }
            execute(connection, "DELETE FROM " + table("team_allies") + " WHERE team_id_1=? OR team_id_2=?", teamId, teamId);
            execute(connection, "DELETE FROM " + table("team_ally_requests") + " WHERE sender_team_id=? OR target_team_id=?", teamId, teamId);
            execute(connection, "DELETE FROM " + CHESTS + " WHERE team_id=?", teamId);
            execute(connection, "DELETE FROM " + OPERATIONS + " WHERE team_id=? AND kind=?", teamId, "IDENTITY");
            execute(connection, "UPDATE " + OPERATIONS + " SET phase=?,updated_at=? WHERE team_id=? AND kind=?", "CANCELLED", System.currentTimeMillis(), teamId, "INVITE");
            return execute(connection, "DELETE FROM " + table("teams") + " WHERE id=? AND owner_uuid=?", teamId, expectedOwner) == 1;
        });
    }

    public synchronized boolean setWarp(int teamId, String name, String location, String serverName, String password, int maxWarps) throws SQLException {
        requireText(name, 32); requireText(location, 255); requireText(serverName, 64);
        if (password != null && password.length() > 64) throw new IllegalArgumentException("Warp password too long");
        if (maxWarps < 1) return false;
        return transaction(connection -> {
            if (team(connection, teamId, true).isEmpty()) return false;
            Optional<Map<String, Object>> old = first(connection, "SELECT * FROM " + table("team_warps") + " WHERE team_id=? AND LOWER(warp_name)=LOWER(?) FOR UPDATE", teamId, name);
            if (old.isPresent()) {
                // Preserve the donor server_name column and existing warp id.
                return execute(connection, "UPDATE " + table("team_warps") + " SET location=?,server_name=?,password=? WHERE id=? AND team_id=?", location, serverName, password, old.get().get("id"), teamId) == 1;
            }
            if (count(connection, "team_warps", "team_id", teamId) >= maxWarps) return false;
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("team_id", teamId); values.put("warp_name", name); values.put("location", location); values.put("server_name", serverName); values.put("password", password);
            return insert(connection, "team_warps", values) == 1;
        });
    }
    public synchronized boolean deleteWarp(int teamId, String name) throws SQLException {
        return transaction(connection -> team(connection, teamId, true).isPresent() && execute(connection, "DELETE FROM " + table("team_warps") + " WHERE team_id=? AND LOWER(warp_name)=LOWER(?)", teamId, name) > 0);
    }

    public synchronized boolean upgrade(int teamId, int expectedTier, int newTier, double cost) throws SQLException {
        if (!Double.isFinite(cost) || cost < 0 || expectedTier < 1 || newTier != expectedTier + 1) throw new IllegalArgumentException("Invalid upgrade");
        return transaction(connection -> {
            if (team(connection, teamId, true).isEmpty() || isFrozen(connection, teamId, null)) return false;
            return execute(connection, "UPDATE " + table("teams") + " SET balance=balance-?,tier=? WHERE id=? AND tier=? AND balance>=?", cost, newTier, teamId, expectedTier, cost) == 1;
        });
    }

    public synchronized BankResult mutateBank(String opid, int teamId, UUID player, double delta, double maxBalance, boolean requireWithdraw, boolean bypass) throws SQLException {
        requireOpid(opid);
        if (!Double.isFinite(delta) || delta == 0 || !Double.isFinite(maxBalance) || maxBalance < 0) throw new IllegalArgumentException("Invalid bank amount/cap");
        Objects.requireNonNull(player);
        return transaction(connection -> {
            Optional<Team> team = team(connection, teamId, true);
            Optional<Operation> previous = operation(connection, opid, true);
            double balance = team.map(Team::balance).orElse(Double.NaN);
            Operation operation;
            if (previous.isPresent()) {
                operation = previous.get();
                checkOperationIdentity(operation, teamId, player, Math.abs(delta));
                if (operation.effectApplied()) {
                    if (Double.compare(Json.doubleValue(operation.payload(), "bankDelta", delta), delta) != 0) throw new SQLException("Operation id reused with opposite bank delta");
                    return new BankResult(true, true, Json.doubleValue(operation.payload(), "bankBalanceAfter", balance), operation, "ALREADY_APPLIED");
                }
                if (TERMINAL_PHASES.contains(operation.phase()) || Set.of("FROZEN", "UNCERTAIN").contains(operation.phase())) return new BankResult(false, true, balance, operation, operation.phase());
            } else operation = beginOperation(connection, opid, teamId, player, "BANK", Math.abs(delta), "PREPARED", "{}");
            Optional<Member> member = member(connection, player, true);
            String rejection = null;
            if (team.isEmpty()) rejection = "NO_TEAM";
            else if (isFrozen(connection, teamId, player)) rejection = "FROZEN";
            else if (member.isEmpty() || member.get().teamId() != teamId) rejection = "NOT_MEMBER";
            else if (requireWithdraw && !bypass && !member.get().flag("can_withdraw")) rejection = "WITHDRAW_DENIED";
            else if (delta > 0 && balance + delta > maxBalance) rejection = "CAP";
            else if (balance + delta < 0) rejection = "UNDERFLOW";
            if (rejection != null) {
                String payload = Json.put(operation.payload(), "rejection", Json.string(rejection));
                Operation rejected = writeOperation(connection, operation, "REJECTED", payload);
                return new BankResult(false, false, balance, rejected, rejection);
            }
            int changed = execute(connection, "UPDATE " + table("teams") + " SET balance=balance+? WHERE id=? AND balance+?>=0 AND (?<=0 OR balance+?<=?)", delta, teamId, delta, delta, delta, maxBalance);
            if (changed != 1) throw new SQLException("Conditional bank update changed no row after locks");
            double after = team(connection, teamId, false).orElseThrow(() -> new SQLException("Team vanished during bank update")).balance();
            String payload = Json.put(operation.payload(), "bankBalanceBefore", Double.toString(balance));
            payload = Json.put(payload, "bankBalanceAfter", Double.toString(after));
            payload = Json.put(payload, "bankDelta", Double.toString(delta));
            payload = Json.put(payload, "effectApplied", "true");
            Operation applied = writeOperation(connection, operation, "TEAM_APPLIED", payload, true);
            return new BankResult(true, false, after, applied, "APPLIED");
        });
    }

    /** Caller authorizes admin access. This is a journaled bank reset, with no player economy leg. */
    public synchronized boolean setBalanceAdmin(int teamId, UUID expectedOwner, UUID actor, double expectedBalance, double newBalance, String opid) throws SQLException {
        requireOpid(opid); Objects.requireNonNull(expectedOwner); Objects.requireNonNull(actor);
        if (!Double.isFinite(expectedBalance) || expectedBalance < 0 || !Double.isFinite(newBalance) || newBalance < 0) return false;
        double delta = newBalance - expectedBalance;
        return transaction(connection -> {
            Optional<Team> team = team(connection, teamId, true);
            Optional<Operation> receipt = operation(connection, opid, true);
            if (receipt.isPresent()) {
                Operation existing = receipt.get();
                checkOperationIdentity(existing, teamId, actor, Math.abs(delta));
                if (!existing.kind().equals("BANK") || !Json.booleanValue(existing.payload(), "admin") || !Objects.equals(Json.rawValue(existing.payload(), "expectedOwner"), Json.string(expectedOwner.toString())) || Double.compare(Json.doubleValue(existing.payload(), "bankBalanceBefore", Double.NaN), expectedBalance) != 0 || Double.compare(Json.doubleValue(existing.payload(), "bankBalanceAfter", Double.NaN), newBalance) != 0) throw new SQLException("Admin operation id reused with different reset");
                return existing.effectApplied() && existing.phase().equals("DONE");
            }
            if (team.isEmpty() || !team.get().owner().equals(expectedOwner) || Double.compare(team.get().balance(), expectedBalance) != 0 || isFrozen(connection, teamId, actor) || hasUnsettledFinancialOperation(connection, teamId, actor)) return false;
            if (execute(connection, "UPDATE " + table("teams") + " SET balance=? WHERE id=? AND owner_uuid=? AND balance=?", newBalance, teamId, expectedOwner, expectedBalance) != 1) throw new SQLException("Admin balance reset changed unexpected rows");
            String payload = Json.put("{}", "admin", "true");
            payload = Json.put(payload, "actor", Json.string(actor.toString()));
            payload = Json.put(payload, "expectedOwner", Json.string(expectedOwner.toString()));
            payload = Json.put(payload, "bankBalanceBefore", Double.toString(expectedBalance));
            payload = Json.put(payload, "bankBalanceAfter", Double.toString(newBalance));
            payload = Json.put(payload, "bankDelta", Double.toString(delta));
            Operation created = beginOperation(connection, opid, teamId, actor, "BANK", Math.abs(delta), "PREPARED", payload);
            writeOperation(connection, created, "DONE", payload, true);
            return true;
        });
    }

    public synchronized Operation beginOperation(String opid, int teamId, UUID player, String kind, double amount, String phase, String payload) throws SQLException {
        try {
            return transaction(connection -> beginOperation(connection, opid, teamId, player, kind, amount, phase, payload));
        } catch (SQLException failure) {
            if (failure.getSQLState() == null || !Set.of("23505", "23000").contains(failure.getSQLState())) throw failure;
            // A concurrent inserter may have committed this same primary key. Its record is truth.
            Optional<Operation> existing = operation(opid);
            if (existing.isEmpty()) throw failure;
            checkOperationIdentity(existing.get(), teamId, player, amount);
            if (!existing.get().kind().equals(kind)) throw failure;
            return existing.get();
        }
    }
    private Operation beginOperation(Connection connection, String opid, int teamId, UUID player, String kind, double amount, String phase, String payload) throws SQLException {
        requireOpid(opid); requireText(kind, 64); requireText(phase, 32);
        if (phase.equals("TEAM_APPLIED")) throw new SQLException("TEAM_APPLIED requires an atomic bank mutation");
        if (!Double.isFinite(amount)) throw new IllegalArgumentException("Invalid operation amount");
        // A new financial intent and destructive actions share the same team-row lock.
        Optional<Team> financialTeam = FINANCIAL_KINDS.contains(kind) && teamId > 0 ? team(connection, teamId, true) : Optional.empty();
        Optional<Operation> existing = operation(connection, opid, true);
        if (existing.isPresent()) {
            checkOperationIdentity(existing.get(), teamId, player, amount);
            if (!existing.get().kind().equals(kind)) throw new SQLException("Operation id reused with different kind");
            return existing.get();
        }
        if (FINANCIAL_KINDS.contains(kind) && teamId > 0 && financialTeam.isEmpty()) throw new SQLException("Financial intent requires an existing team");
        payload = Json.put(payload, "effectApplied", "false");
        long now = System.currentTimeMillis();
        execute(connection, "INSERT INTO " + OPERATIONS + " (opid,team_id,player_uuid,kind,amount,phase,payload,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)", opid, teamId, player, kind, amount, phase, payload, now, now);
        return new Operation(opid, teamId, player, kind, amount, phase, payload, now, now);
    }
    public synchronized Operation updateOperation(String opid, String phase, String payload) throws SQLException {
        requireText(phase, 32);
        return transaction(connection -> writeOperation(connection, operation(connection, opid, true).orElseThrow(() -> new SQLException("Unknown operation")), phase, payload));
    }
    public Optional<Operation> operation(String opid) throws SQLException {
        try (Connection connection = factory.open()) { return operation(connection, opid, false); }
    }
    public List<Operation> pendingOperations() throws SQLException {
        try (Connection connection = factory.open()) {
            return query(connection, "SELECT * FROM " + OPERATIONS + " WHERE kind<>? AND kind<>? ORDER BY created_at,opid", "INVITE", "IDENTITY").stream().map(TeamStore::operationRow).filter(operation -> !TERMINAL_PHASES.contains(operation.phase())).toList();
        }
    }
    public boolean isFrozen(int teamId, UUID player) throws SQLException {
        try (Connection connection = factory.open()) { return isFrozen(connection, teamId, player); }
    }
    public synchronized Operation freezeOperation(String opid, String reason) throws SQLException {
        return transaction(connection -> {
            Operation existing = operation(connection, opid, true).orElseThrow(() -> new SQLException("Unknown operation"));
            return writeOperation(connection, existing, "FROZEN", Json.put(existing.payload(), "freezeReason", Json.string(reason)));
        });
    }

    public Optional<Chest> chest(int teamId) throws SQLException {
        try (Connection connection = factory.open()) { return chest(connection, teamId, false); }
    }
    public synchronized boolean saveChest(int teamId, int formatVersion, String inventoryJson, String originalSha256, long expectedGeneration) throws SQLException {
        if (formatVersion < 1 || inventoryJson == null || expectedGeneration < 0 || originalSha256 != null && !originalSha256.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("Invalid chest data");
        return transaction(connection -> {
            if (team(connection, teamId, true).isEmpty()) return false;
            Optional<Chest> existing = chest(connection, teamId, true);
            if (existing.isEmpty()) {
                if (expectedGeneration != 0) return false;
                execute(connection, "INSERT INTO " + CHESTS + " (team_id,format_version,inventory_json,original_sha256,generation,updated_at) VALUES (?,?,?,?,?,?)", teamId, formatVersion, inventoryJson, originalSha256, 1L, System.currentTimeMillis());
                return true;
            }
            if (existing.get().generation() != expectedGeneration) return false;
            if (!Objects.equals(existing.get().originalSha256(), originalSha256)) throw new SQLException("Original chest hash cannot change");
            return execute(connection, "UPDATE " + CHESTS + " SET format_version=?,inventory_json=?,generation=generation+1,updated_at=? WHERE team_id=? AND generation=?", formatVersion, inventoryJson, System.currentTimeMillis(), teamId, expectedGeneration) == 1;
        });
    }

    /** Cold conversion import: validates every source/hash/replay before inserting any addon row. */
    public synchronized boolean importChests(List<ChestSeed> seeds) throws SQLException {
        Objects.requireNonNull(seeds);
        Map<Integer, ChestSeed> ordered = new java.util.TreeMap<>();
        for (ChestSeed seed : seeds) {
            if (seed == null || seed.teamId() < 1 || seed.teamId() == Integer.MAX_VALUE || seed.formatVersion() < 1 || seed.inventoryJson() == null || seed.inventoryJson().isBlank() || seed.originalSha256() == null || !seed.originalSha256().matches("[a-f0-9]{64}") || ordered.putIfAbsent(seed.teamId(), seed) != null) return false;
        }
        return transaction(connection -> {
            for (int teamId : ordered.keySet()) if (team(connection, teamId, true).isEmpty()) return false;
            List<ChestSeed> inserts = new ArrayList<>();
            for (ChestSeed seed : ordered.values()) {
                Optional<Map<String, Object>> donor = first(connection, "SELECT * FROM " + table("team_enderchest") + " WHERE team_id=? FOR UPDATE", seed.teamId());
                if (donor.isEmpty() || !(donor.get().get("inventory_data") instanceof String original) || original.isEmpty() || !sha256Utf8(original).equals(seed.originalSha256())) return false;
                Optional<Chest> existing = chest(connection, seed.teamId(), true);
                if (existing.isPresent()) {
                    Chest old = existing.get();
                    if (old.generation() != 1 || old.formatVersion() != seed.formatVersion() || !old.inventoryJson().equals(seed.inventoryJson()) || !Objects.equals(old.originalSha256(), seed.originalSha256())) return false;
                } else inserts.add(seed);
            }
            long now = System.currentTimeMillis();
            for (ChestSeed seed : inserts) {
                if (execute(connection, "INSERT INTO " + CHESTS + " (team_id,format_version,inventory_json,original_sha256,generation,updated_at) VALUES (?,?,?,?,?,?)", seed.teamId(), seed.formatVersion(), seed.inventoryJson(), seed.originalSha256(), 1L, now) != 1) throw new SQLException("Chest import changed unexpected rows");
            }
            return true;
        });
    }

    public synchronized boolean insertRow(String suffix, Map<String, Object> values) throws SQLException {
        checkGeneric(suffix, values);
        return transaction(connection -> { lockRowTeams(connection, suffix, values); return insert(connection, suffix, values) == 1; });
    }
    public synchronized boolean setMemberSort(int teamId,UUID actor,String sort)throws SQLException{
        if(!Set.of("JOIN_DATE","ALPHABETICAL","ONLINE_STATUS").contains(sort))throw new IllegalArgumentException("Invalid member sort");
        return transaction(connection->{if(team(connection,teamId,true).isEmpty()||member(connection,actor,true).filter(m->m.teamId()==teamId).isEmpty())return false;var existing=first(connection,"SELECT data_key FROM "+table("team_custom_data")+" WHERE team_id=? AND data_key=? FOR UPDATE",teamId,"member_sort_type");if(existing.isEmpty())return insert(connection,"team_custom_data",Map.of("team_id",teamId,"data_key","member_sort_type","data_value",sort))==1;return execute(connection,"UPDATE "+table("team_custom_data")+" SET data_value=?,updated_at=CURRENT_TIMESTAMP WHERE team_id=? AND data_key=?",sort,teamId,"member_sort_type")==1;});
    }
    public synchronized int updateRows(String suffix, Map<String, Object> where, Map<String, Object> values) throws SQLException {
        checkGeneric(suffix, values); checkWhere(suffix, where);
        return transaction(connection -> { lockRowTeams(connection, suffix, where); lockRowTeams(connection, suffix, values); return update(connection, suffix, where, values); });
    }
    public int updateRows(String suffix, String whereColumn, Object value, Map<String, Object> values) throws SQLException {
        return updateRows(suffix, singleton(whereColumn, value), values);
    }
    public synchronized int deleteRows(String suffix, Map<String, Object> where) throws SQLException {
        if (!GENERIC_WRITES.contains(suffix)) throw new IllegalArgumentException("Suffix is not mutable through generic API");
        checkWhere(suffix, where);
        return transaction(connection -> {
            lockRowTeams(connection, suffix, where);
            return execute(connection, "DELETE FROM " + table(suffix) + " WHERE " + predicates(where), where.values().toArray());
        });
    }
    public int deleteRows(String suffix, String whereColumn, Object value) throws SQLException { return deleteRows(suffix, singleton(whereColumn, value)); }

    private Optional<Team> team(Connection connection, int id, boolean lock) throws SQLException {
        return first(connection, "SELECT * FROM " + table("teams") + " WHERE id=?" + (lock ? " FOR UPDATE" : ""), id).map(Team::new);
    }
    private Optional<Member> member(Connection connection, UUID player, boolean lock) throws SQLException {
        return first(connection, "SELECT * FROM " + table("team_members") + " WHERE player_uuid=?" + (lock ? " FOR UPDATE" : ""), player).map(Member::new);
    }
    private boolean blacklisted(Connection connection, int teamId, UUID player) throws SQLException {
        return first(connection, "SELECT id FROM " + table("team_blacklist") + " WHERE team_id=? AND player_uuid=?", teamId, player).isPresent();
    }
    private Optional<Operation> operation(Connection connection, String opid, boolean lock) throws SQLException {
        return first(connection, "SELECT * FROM " + OPERATIONS + " WHERE opid=?" + (lock ? " FOR UPDATE" : ""), opid).map(TeamStore::operationRow);
    }
    private static Operation operationRow(Map<String, Object> row) {
        String player = RowData.text(row.get("player_uuid"));
        return new Operation(RowData.text(row.get("opid")), RowData.integer(row.get("team_id")), player == null ? null : UUID.fromString(player), RowData.text(row.get("kind")), RowData.decimal(row.get("amount")), RowData.text(row.get("phase")), RowData.text(row.get("payload")), ((Number) row.get("created_at")).longValue(), ((Number) row.get("updated_at")).longValue());
    }
    private Operation writeOperation(Connection connection, Operation old, String phase, String payload) throws SQLException {
        if (phase.equals("TEAM_APPLIED") && !old.effectApplied()) throw new SQLException("TEAM_APPLIED requires an atomic bank mutation");
        return writeOperation(connection, old, phase, payload, old.effectApplied());
    }
    private Operation writeOperation(Connection connection, Operation old, String phase, String payload, boolean effectApplied) throws SQLException {
        requireText(phase, 32);
        // Callers may add data or change phase, but cannot invent/reset a committed bank effect.
        payload = Json.put(Json.merge(old.payload(), payload), "effectApplied", Boolean.toString(effectApplied));
        if (old.effectApplied()) {
            for (String field : List.of("bankBalanceBefore", "bankBalanceAfter", "bankDelta")) {
                String original = Json.rawValue(old.payload(), field);
                if (original != null) payload = Json.put(payload, field, original);
            }
        }
        long now = System.currentTimeMillis();
        if (execute(connection, "UPDATE " + OPERATIONS + " SET phase=?,payload=?,updated_at=? WHERE opid=?", phase, payload, now, old.opid()) != 1) throw new SQLException("Operation vanished");
        return new Operation(old.opid(), old.teamId(), old.player(), old.kind(), old.amount(), phase, payload, old.createdAt(), now);
    }
    private boolean isFrozen(Connection connection, int teamId, UUID player) throws SQLException {
        return first(connection, "SELECT opid FROM " + OPERATIONS + " WHERE ((team_id=? AND team_id>0) OR player_uuid=?) AND phase IN (?,?) AND kind<>?", teamId, player, "FROZEN", "UNCERTAIN", "INVITE").isPresent();
    }
    private boolean hasUnsettledFinancialOperation(Connection connection, int teamId, UUID player) throws SQLException {
        return query(connection, "SELECT kind,phase FROM " + OPERATIONS + " WHERE (team_id=? AND team_id>0) OR player_uuid=?", teamId, player).stream()
                .anyMatch(row -> FINANCIAL_KINDS.contains(RowData.text(row.get("kind"))) && !FINANCIAL_TERMINAL_PHASES.contains(RowData.text(row.get("phase"))));
    }
    private Optional<Chest> chest(Connection connection, int teamId, boolean lock) throws SQLException {
        return first(connection, "SELECT * FROM " + CHESTS + " WHERE team_id=?" + (lock ? " FOR UPDATE" : ""), teamId).map(row -> new Chest(RowData.integer(row.get("team_id")), RowData.integer(row.get("format_version")), RowData.text(row.get("inventory_json")), RowData.text(row.get("original_sha256")), ((Number) row.get("generation")).longValue(), ((Number) row.get("updated_at")).longValue()));
    }
    private void checkOperationIdentity(Operation operation, int teamId, UUID player, double amount) throws SQLException {
        if (operation.teamId() != teamId || !Objects.equals(operation.player(), player) || Double.compare(Math.abs(operation.amount()), Math.abs(amount)) != 0) throw new SQLException("Operation id reused with different transaction");
    }
    private boolean identityExists(Connection connection, String column, String value, int excludeId) throws SQLException {
        return first(connection, "SELECT id FROM " + table("teams") + " WHERE LOWER(" + quote(column) + ")=LOWER(?) AND id<>? FOR UPDATE", value, excludeId).isPresent();
    }
    private void claimIdentity(Connection connection, String column, String value, int teamId, UUID owner) throws SQLException {
        String id = identityId(column, value);
        Optional<Operation> existing = operation(connection, id, true);
        if (existing.isPresent()) {
            if (existing.get().teamId() != teamId) throw new SQLException("Team tag already reserved");
        } else beginOperation(connection, id, teamId, owner, "IDENTITY", 0, "IDENTITY", "{}");
    }
    private static String identityId(String column, String value) {
        // Hash keeps arbitrarily long UTF-8 tags within the journal primary-key limit.
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "identity:" + column + ":" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String canonical(String value) { return value.toLowerCase(Locale.ROOT); }
    private static String sha256Utf8(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String inviteId(int teamId, UUID target) { return "invite:" + teamId + ":" + target; }
    private void cachePlayer(Connection connection, UUID player, String name) throws SQLException {
        if (first(connection, "SELECT player_uuid FROM " + table("player_cache") + " WHERE player_uuid=? FOR UPDATE", player).isPresent()) {
            execute(connection, "UPDATE " + table("player_cache") + " SET player_name=?,last_seen=CURRENT_TIMESTAMP WHERE player_uuid=?", name, player);
        } else insert(connection, "player_cache", Map.of("player_uuid", player, "player_name", name));
    }
    private long count(Connection connection, String suffix, String column, Object value) throws SQLException {
        return ((Number) first(connection, "SELECT COUNT(*) AS row_count FROM " + table(suffix) + " WHERE " + quote(column) + "=?", value).orElseThrow().get("row_count")).longValue();
    }
    private void lockRowTeams(Connection connection, String suffix, Map<String, Object> values) throws SQLException {
        for (String column : List.of("team_id", "team_id_1", "team_id_2", "sender_team_id", "target_team_id")) {
            if (values.containsKey(column)) {
                int id = ((Number) values.get(column)).intValue();
                if (team(connection, id, true).isEmpty()) throw new SQLException("Missing team for " + suffix);
            }
        }
    }
    private int insert(Connection connection, String suffix, Map<String, Object> values) throws SQLException {
        checkWhere(suffix, values);
        String columns = String.join(",", values.keySet().stream().map(TeamStore::quote).toList());
        String markers = String.join(",", Collections.nCopies(values.size(), "?"));
        return execute(connection, "INSERT INTO " + table(suffix) + " (" + columns + ") VALUES (" + markers + ")", values.values().toArray());
    }
    private int update(Connection connection, String suffix, Map<String, Object> where, Map<String, Object> values) throws SQLException {
        checkWhere(suffix, where); checkWhere(suffix, values);
        List<Object> parameters = new ArrayList<>(values.values());
        parameters.addAll(where.values());
        return execute(connection, "UPDATE " + table(suffix) + " SET " + String.join(",", values.keySet().stream().map(column -> quote(column) + "=?").toList()) + " WHERE " + predicates(where), parameters.toArray());
    }
    private static String predicates(Map<String, Object> values) { return String.join(" AND ", values.keySet().stream().map(column -> quote(column) + "=?").toList()); }
    private static Map<String, Object> singleton(String column, Object value) {
        Map<String, Object> map = new LinkedHashMap<>(); map.put(column, value); return map;
    }
    private void checkGeneric(String suffix, Map<String, Object> values) {
        if (!GENERIC_WRITES.contains(suffix)) throw new IllegalArgumentException("Suffix is not mutable through generic API");
        checkWhere(suffix, values);
        if (values.containsKey("id")) throw new IllegalArgumentException("Generic API cannot change generated ids");
    }
    private void checkWhere(String suffix, Map<String, ?> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("Empty SQL values/filter");
        values.keySet().forEach(column -> checkColumn(suffix, column));
    }
    private static void checkFlags(Map<String, Boolean> flags) {
        if (flags.isEmpty() || !FLAGS.containsAll(flags.keySet()) || flags.values().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Invalid member flags");
    }
    private void checkSuffix(String suffix) { if (!DonorSchema.COLUMNS.containsKey(suffix)) throw new IllegalArgumentException("Unknown donor table suffix"); }
    private void checkColumn(String suffix, String column) {
        checkSuffix(suffix);
        if (!DonorSchema.COLUMNS.get(suffix).containsKey(column)) throw new IllegalArgumentException("Unknown donor column");
    }
    private String table(String suffix) { checkSuffix(suffix); return quote(prefix + suffix); }
    private static String quote(String identifier) { return "`" + identifier + "`"; }
    private static void requireText(String text, int maxLength) {
        if (text == null || text.isBlank() || text.length() > maxLength) throw new IllegalArgumentException("Invalid text length");
    }
    private static void requireOpid(String opid) { requireText(opid, 192); }
    private static boolean compatible(String expected, int actual) {
        if (expected.startsWith("VARCHAR")) return Set.of(Types.VARCHAR, Types.NVARCHAR, Types.CHAR, Types.LONGVARCHAR).contains(actual);
        if (expected.contains("TEXT")) return Set.of(Types.CLOB, Types.NCLOB, Types.VARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR).contains(actual);
        if (expected.equals("BOOLEAN")) return Set.of(Types.BOOLEAN, Types.BIT, Types.TINYINT).contains(actual);
        if (expected.equals("INT")) return actual == Types.INTEGER;
        if (expected.equals("BIGINT")) return actual == Types.BIGINT;
        if (expected.equals("DOUBLE")) return actual == Types.DOUBLE || actual == Types.FLOAT;
        if (expected.equals("TIMESTAMP")) return actual == Types.TIMESTAMP;
        return false;
    }
    private static boolean wideEnough(String expected, int precision) {
        if (!expected.startsWith("VARCHAR(")) return true;
        int minimum = Integer.parseInt(expected.substring(8, expected.length() - 1));
        return precision >= minimum;
    }
    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            Object value = parameters[i];
            statement.setObject(i + 1, value instanceof UUID uuid ? uuid.toString() : value);
        }
    }
    private static int execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { bind(statement, parameters); return statement.executeUpdate(); }
    }
    private static Optional<Map<String, Object>> first(Connection connection, String sql, Object... parameters) throws SQLException {
        List<Map<String, Object>> rows = query(connection, sql, parameters);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
    private static List<Map<String, Object>> query(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData metadata = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        Object value = result.getObject(i);
                        if (value instanceof java.sql.Clob clob) value = clob.getSubString(1, Math.toIntExact(clob.length()));
                        row.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), value);
                    }
                    rows.add(RowData.copy(row));
                }
                return List.copyOf(rows);
            }
        }
    }
    @FunctionalInterface private interface Transaction<T> { T run(Connection connection) throws SQLException; }
    private <T> T transaction(Transaction<T> work) throws SQLException {
        try (Connection connection = factory.open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }
}
