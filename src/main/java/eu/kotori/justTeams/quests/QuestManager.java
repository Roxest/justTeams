package eu.kotori.justTeams.quests;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.util.CancellableTask;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public final class QuestManager {
   private final JustTeams plugin;
   private final Map<String, Quest> quests = new LinkedHashMap<>();
   private final Map<Integer, Map<String, QuestProgress>> teamProgress = new ConcurrentHashMap<>();
   private final Set<Integer> loadingTeams = Collections.newSetFromMap(new ConcurrentHashMap<>());
   private final Set<String> dirty = Collections.newSetFromMap(new ConcurrentHashMap<>());
   private final Random random = new Random();
   private CancellableTask flushTask;
   private boolean enabled;
   private int autoAssignCount;

   public QuestManager(JustTeams plugin) {
      this.plugin = plugin;
   }

   public void load() {
      this.quests.clear();
      File file = new File(this.plugin.getDataFolder(), "quests.yml");
      if (!file.exists()) {
         this.plugin.saveResource("quests.yml", false);
      }

      FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

      try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(this.plugin.getResource("quests.yml")), StandardCharsets.UTF_8)) {
         cfg.setDefaults(YamlConfiguration.loadConfiguration(reader));
      } catch (IOException var12) {
      }

      this.enabled = cfg.getBoolean("settings.enabled", true);
      this.autoAssignCount = cfg.getInt("settings.auto_assign_count", 3);
      ConfigurationSection pool = cfg.getConfigurationSection("quests");
      if (pool != null) {
         for (String id : pool.getKeys(false)) {
            ConfigurationSection s = pool.getConfigurationSection(id);
            if (s != null) {
               try {
                  QuestType type = QuestType.valueOf(s.getString("type", "KILL_MOBS").toUpperCase());
                  Quest q = new Quest(
                     id,
                     s.getString("name", id),
                     s.getStringList("description"),
                     type,
                     s.getString("target", "ANY"),
                     s.getLong("required", 1L),
                     s.getLong("reward_points", 0L),
                     s.getDouble("reward_money", 0.0),
                     s.getInt("duration_seconds", 0),
                     s.getString("icon", "PAPER")
                  );
                  this.quests.put(id, q);
               } catch (IllegalArgumentException e) {
                  this.plugin.getLogger().warning("Invalid quest type for " + id + ": " + e.getMessage());
               }
            }
         }
      }

      this.plugin.getLogger().info("Loaded " + this.quests.size() + " quest definitions");
   }

   public void shutdown() {
      if (this.flushTask != null) {
         this.flushTask.cancel();
         this.flushTask = null;
      }

      this.flushAll();
      this.teamProgress.clear();
      this.quests.clear();
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   public Quest getQuest(String id) {
      return id == null ? null : this.quests.get(id);
   }

   public Collection<Quest> getAllQuests() {
      return Collections.unmodifiableCollection(this.quests.values());
   }

   public Map<String, QuestProgress> getProgress(int teamId) {
      Map<String, QuestProgress> existing = this.teamProgress.get(teamId);
      if (existing != null) {
         return existing;
      }

      Map<String, QuestProgress> placeholder = new ConcurrentHashMap<>();
      Map<String, QuestProgress> prev = this.teamProgress.putIfAbsent(teamId, placeholder);
      Map<String, QuestProgress> target = prev != null ? prev : placeholder;
      this.triggerLoad(teamId, target);
      return target;
   }

   public void preloadTeam(int teamId) {
      Map<String, QuestProgress> placeholder = new ConcurrentHashMap<>();
      Map<String, QuestProgress> prev = this.teamProgress.putIfAbsent(teamId, placeholder);
      this.triggerLoad(teamId, prev != null ? prev : placeholder);
   }

   private void triggerLoad(int teamId, Map<String, QuestProgress> target) {
      if (this.loadingTeams.add(teamId)) {
         this.plugin.getTaskRunner().runAsync(() -> {
            try {
               for (QuestProgress qp : this.plugin.getStorageManager().getStorage().loadQuestProgress(teamId)) {
                  target.putIfAbsent(qp.getQuestId(), qp);
               }
            } finally {
               this.loadingTeams.remove(teamId);
            }
         });
      }
   }

   private static String dirtyKey(int teamId, String questId) {
      return teamId + ":" + questId;
   }

   private void markDirty(QuestProgress qp) {
      this.dirty.add(dirtyKey(qp.getTeamId(), qp.getQuestId()));
   }

   public void startFlushTask() {
      if (this.flushTask == null) {
         long intervalTicks = 200L;
         this.flushTask = this.plugin.getTaskRunner().runAsyncTaskTimer(this::flushDirty, intervalTicks, intervalTicks);
      }
   }

   private void flushDirty() {
      if (!this.dirty.isEmpty()) {
         List<QuestProgress> snapshot = new ArrayList<>();

         for (Map<String, QuestProgress> map : this.teamProgress.values()) {
            for (QuestProgress qp : map.values()) {
               if (this.dirty.remove(dirtyKey(qp.getTeamId(), qp.getQuestId()))) {
                  snapshot.add(qp);
               }
            }
         }

         for (QuestProgress qp : snapshot) {
            this.plugin
               .getStorageManager()
               .getStorage()
               .saveQuestProgress(qp.getTeamId(), qp.getQuestId(), qp.getProgress(), qp.getStartedAt(), qp.isCompleted(), qp.isClaimed());
         }
      }
   }

   public List<QuestProgress> getActiveQuests(int teamId) {
      Map<String, QuestProgress> map = this.getProgress(teamId);
      this.ensureAutoAssigned(teamId, map);
      List<QuestProgress> out = new ArrayList<>();

      for (QuestProgress qp : map.values()) {
         Quest q = this.quests.get(qp.getQuestId());
         if (q != null && !q.isExpired(qp)) {
            out.add(qp);
         }
      }

      return out;
   }

   private void ensureAutoAssigned(int teamId, Map<String, QuestProgress> map) {
      if (this.enabled && this.autoAssignCount > 0) {
         long active = map.values().stream().filter(p -> !p.isClaimed()).filter(p -> {
            Quest qx = this.quests.get(p.getQuestId());
            return qx != null && !qx.isExpired(p);
         }).count();
         if (active < this.autoAssignCount) {
            List<Quest> pool = new ArrayList<>(this.quests.values());
            Collections.shuffle(pool, this.random);

            for (Quest q : pool) {
               if (active >= this.autoAssignCount) {
                  break;
               }

               if (map.containsKey(q.getId())) {
                  QuestProgress existing = map.get(q.getId());
                  if (existing.isClaimed() || q.isExpired(existing)) {
                     continue;
                  }
               }

               QuestProgress qp = new QuestProgress(teamId, q.getId(), 0L, System.currentTimeMillis(), false, false);
               map.put(q.getId(), qp);
               this.markDirty(qp);
               active++;
            }
         }
      }
   }

   public boolean assignQuest(int teamId, String questId) {
      Quest q = this.quests.get(questId);
      if (q == null) {
         return false;
      }

      Map<String, QuestProgress> map = this.getProgress(teamId);
      if (map.containsKey(questId) && !map.get(questId).isClaimed()) {
         return false;
      }

      QuestProgress qp = new QuestProgress(teamId, questId, 0L, System.currentTimeMillis(), false, false);
      map.put(questId, qp);
      this.markDirty(qp);
      return true;
   }

   public void resetQuests(int teamId) {
      Map<String, QuestProgress> map = this.teamProgress.remove(teamId);
      if (map != null) {
         for (String questId : new ArrayList<>(map.keySet())) {
            String fid = questId;
            this.plugin.getTaskRunner().runAsync(() -> this.plugin.getStorageManager().getStorage().deleteQuestProgress(teamId, fid));
         }
      }
   }

   public boolean forceComplete(int teamId, String questId) {
      Map<String, QuestProgress> map = this.getProgress(teamId);
      QuestProgress qp = map.get(questId);
      Quest q = this.quests.get(questId);
      if (qp != null && q != null) {
         qp.setProgress(q.getRequired());
         qp.markCompleted();
         this.markDirty(qp);
         return true;
      } else {
         return false;
      }
   }

   public void onEvent(int teamId, QuestType type, String target, long amount) {
      if (this.enabled && amount > 0L) {
         Map<String, QuestProgress> map = this.getProgress(teamId);
         this.ensureAutoAssigned(teamId, map);

         for (QuestProgress qp : map.values()) {
            if (!qp.isCompleted() && !qp.isClaimed()) {
               Quest q = this.quests.get(qp.getQuestId());
               if (q != null && q.getType() == type && !q.isExpired(qp) && q.matchesTarget(target)) {
                  long newP = qp.addProgress(amount);
                  if (newP >= q.getRequired()) {
                     qp.setProgress(q.getRequired());
                     qp.markCompleted();
                     this.announceCompletion(teamId, q);
                  }

                  this.markDirty(qp);
               }
            }
         }
      }
   }

   public void addProgress(int teamId, QuestType type, String target, long amount) {
      if (type != null) {
         this.onEvent(teamId, type, target, amount);
      }
   }

   public void addCustomProgress(int teamId, String objectiveKey, long amount) {
      this.onEvent(teamId, QuestType.CUSTOM, objectiveKey, amount);
   }

   public void addCustomProgress(Team team, String objectiveKey, long amount) {
      if (team != null) {
         this.addCustomProgress(team.getId(), objectiveKey, amount);
      }
   }

   public boolean addCustomProgress(Player player, String objectiveKey, long amount) {
      if (player == null) {
         return false;
      }

      Team team = this.plugin.getTeamManager().getPlayerTeamCached(player.getUniqueId());
      if (team == null) {
         return false;
      }

      this.addCustomProgress(team.getId(), objectiveKey, amount);
      return true;
   }

   private void announceCompletion(int teamId, Quest q) {
      Team team = this.plugin.getTeamManager().getTeamById(teamId).orElse(null);
      if (team != null) {
         team.getMembers()
            .forEach(
               m -> {
                  Player p = m.getBukkitPlayer();
                  if (p != null && p.isOnline()) {
                     this.plugin
                        .getMessageManager()
                        .sendRawMessage(p, "<gold>Quest complete: <yellow>" + q.getDisplayName() + "<gold>! Open <yellow>/team quests<gold> to claim.");
                  }
               }
            );
      }
   }

   public boolean claimReward(int teamId, String questId, Player claimer) {
      Map<String, QuestProgress> map = this.getProgress(teamId);
      QuestProgress qp = map.get(questId);
      Quest q = this.quests.get(questId);
      if (qp == null || q == null) {
         return false;
      }

      if (!qp.isCompleted()) {
         return false;
      }

      if (!qp.tryClaim()) {
         return false;
      }

      if (q.getRewardPoints() > 0L) {
         this.plugin.getTeamManager().addTeamPoints(teamId, q.getRewardPoints(), "quest:" + questId);
      }

      if (q.getRewardMoney() > 0.0 && this.plugin.getEconomy() != null) {
         Team team = this.plugin.getTeamManager().getTeamById(teamId).orElse(null);
         if (team != null) {
            double reward = q.getRewardMoney();
            this.plugin.getTaskRunner().runAsync(() -> {
               if (this.plugin.getStorageManager().getStorage().depositToTeamBank(teamId, reward, -1.0)) {
                  double authoritative = this.plugin.getStorageManager().getStorage().getTeamBalance(teamId);
                  double newBalance = authoritative >= 0.0 ? authoritative : team.getBalance() + reward;
                  team.setBalance(newBalance);
                  this.plugin.getTeamManager().markTeamModified(teamId);
                  this.plugin.getTeamManager().publishCrossServerUpdate(teamId, "BANK_DEPOSIT", claimer.getUniqueId().toString(), reward + ":" + newBalance);
               }
            });
         }
      }

      this.markDirty(qp);
      return true;
   }

   public void flushAll() {
      Map<Integer, Map<String, QuestProgress>> snapshot = new HashMap<>(this.teamProgress);

      for (Map<String, QuestProgress> m : snapshot.values()) {
         for (QuestProgress qp : m.values()) {
            this.plugin
               .getStorageManager()
               .getStorage()
               .saveQuestProgress(qp.getTeamId(), qp.getQuestId(), qp.getProgress(), qp.getStartedAt(), qp.isCompleted(), qp.isClaimed());
         }
      }
   }
}
