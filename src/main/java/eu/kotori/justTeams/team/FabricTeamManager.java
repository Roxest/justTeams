package eu.kotori.justTeams.team;

import eu.kotori.justTeams.JustTeamsFabric;
import eu.kotori.justTeams.storage.FabricStorageManager;
import eu.kotori.justTeams.storage.FabricStorageManager.TeamRecord;
import eu.kotori.justTeams.storage.FabricStorageManager.WarpRecord;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class FabricTeamManager {

    private static final Logger LOGGER = Logger.getLogger(FabricTeamManager.class.getName());
    private final JustTeamsFabric plugin;
    private final FabricStorageManager storage;
    
    // Invites cache: targetPlayerUuid -> teamName
    private final Map<UUID, String> pendingInvites = new ConcurrentHashMap<>();

    public FabricTeamManager(JustTeamsFabric plugin, FabricStorageManager storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public FabricStorageManager getStorage() {
        return storage;
    }

    public TeamRecord getPlayerTeam(UUID playerUuid) {
        return storage.getTeamByPlayer(playerUuid);
    }

    public TeamRecord getTeamByName(String name) {
        return storage.getTeamByName(name);
    }

    public boolean createTeam(String name, String tag, UUID ownerUuid, String ownerName) {
        if (storage.getTeamByPlayer(ownerUuid) != null) {
            return false;
        }
        if (storage.getTeamByName(name) != null) {
            return false;
        }
        return storage.createTeam(name, tag, ownerUuid, ownerName);
    }

    public boolean disbandTeam(UUID playerUuid) {
        TeamRecord team = storage.getTeamByPlayer(playerUuid);
        if (team == null) return false;
        if (!team.ownerUuid.equals(playerUuid)) return false;
        return storage.disbandTeam(team.id);
    }

    public void invitePlayer(UUID inviterUuid, UUID targetUuid) {
        TeamRecord team = storage.getTeamByPlayer(inviterUuid);
        if (team != null) {
            pendingInvites.put(targetUuid, team.name);
        }
    }

    public boolean acceptInvite(UUID playerUuid, String teamName) {
        String pending = pendingInvites.get(playerUuid);
        if (pending == null) return false;
        if (!teamName.isEmpty() && !pending.equalsIgnoreCase(teamName)) return false;

        TeamRecord team = storage.getTeamByName(pending);
        if (team == null) {
            pendingInvites.remove(playerUuid);
            return false;
        }

        boolean success = storage.addMember(team.id, playerUuid, "MEMBER");
        if (success) {
            pendingInvites.remove(playerUuid);
        }
        return success;
    }

    public boolean denyInvite(UUID playerUuid, String teamName) {
        String pending = pendingInvites.get(playerUuid);
        if (pending == null) return false;
        if (!teamName.isEmpty() && !pending.equalsIgnoreCase(teamName)) return false;
        pendingInvites.remove(playerUuid);
        return true;
    }

    public boolean leaveTeam(UUID playerUuid) {
        TeamRecord team = storage.getTeamByPlayer(playerUuid);
        if (team == null) return false;
        if (team.ownerUuid.equals(playerUuid)) {
            // Owner must transfer ownership or disband
            return false;
        }
        return storage.removeMember(playerUuid);
    }

    public boolean kickMember(UUID kickerUuid, UUID targetUuid) {
        TeamRecord kickerTeam = storage.getTeamByPlayer(kickerUuid);
        TeamRecord targetTeam = storage.getTeamByPlayer(targetUuid);
        if (kickerTeam == null || targetTeam == null) return false;
        if (kickerTeam.id != targetTeam.id) return false;
        if (targetUuid.equals(kickerTeam.ownerUuid)) return false;
        return storage.removeMember(targetUuid);
    }

    public boolean setHome(UUID playerUuid, String locationStr) {
        TeamRecord team = storage.getTeamByPlayer(playerUuid);
        if (team == null) return false;
        return storage.setTeamHome(team.id, locationStr);
    }

    public String getHome(UUID playerUuid) {
        TeamRecord team = storage.getTeamByPlayer(playerUuid);
        if (team == null) return null;
        return team.homeLocation;
    }

    public boolean delHome(UUID playerUuid) {
        TeamRecord team = storage.getTeamByPlayer(playerUuid);
        if (team == null) return false;
        return storage.delTeamHome(team.id);
    }

    public boolean setWarp(UUID playerUuid, String warpName, String locationStr, String password) {
        TeamRecord team = storage.getTeamByPlayer(playerUuid);
        if (team == null) return false;
        return storage.setTeamWarp(team.id, warpName, locationStr, password);
    }

    public WarpRecord getWarp(UUID playerUuid, String warpName) {
        TeamRecord team = storage.getTeamByPlayer(playerUuid);
        if (team == null) return null;
        return storage.getTeamWarp(team.id, warpName);
    }

    public List<WarpRecord> listWarps(UUID playerUuid) {
        TeamRecord team = storage.getTeamByPlayer(playerUuid);
        if (team == null) return java.util.Collections.emptyList();
        return storage.listTeamWarps(team.id);
    }

    public boolean delWarp(UUID playerUuid, String warpName) {
        TeamRecord team = storage.getTeamByPlayer(playerUuid);
        if (team == null) return false;
        return storage.delTeamWarp(team.id, warpName);
    }

    public boolean depositBank(UUID playerUuid, double amount) {
        TeamRecord team = storage.getTeamByPlayer(playerUuid);
        if (team == null || amount <= 0) return false;
        return storage.depositBank(team.id, amount);
    }

    public boolean withdrawBank(UUID playerUuid, double amount) {
        TeamRecord team = storage.getTeamByPlayer(playerUuid);
        if (team == null || amount <= 0) return false;
        return storage.withdrawBank(team.id, amount);
    }

    public boolean togglePvP(UUID playerUuid) {
        TeamRecord team = storage.getTeamByPlayer(playerUuid);
        if (team == null) return false;
        boolean newStatus = !team.pvpEnabled;
        boolean ok = storage.togglePvP(team.id, newStatus);
        if (ok) team.pvpEnabled = newStatus;
        return ok;
    }

    public List<TeamRecord> getTopTeams(int limit) {
        return storage.getTopTeams(limit);
    }
}
