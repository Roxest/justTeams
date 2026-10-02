package eu.kotori.justTeams.listeners;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamUpgradeManager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class TeamDamageBonusListener implements Listener {
   private final JustTeams plugin;

   public TeamDamageBonusListener(JustTeams plugin) {
      this.plugin = plugin;
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onEntityDamage(EntityDamageByEntityEvent event) {
      TeamUpgradeManager upgrades = this.plugin.getTeamUpgradeManager();
      if (upgrades != null && upgrades.isEnabled()) {
         Player attacker = this.resolveAttacker(event);
         if (attacker != null) {
            if (event.getEntity() instanceof Player victim) {
               if (!attacker.getUniqueId().equals(victim.getUniqueId())) {
                  Team attackerTeam = this.plugin.getTeamManager().getPlayerTeam(attacker.getUniqueId());
                  if (attackerTeam != null) {
                     Team victimTeam = this.plugin.getTeamManager().getPlayerTeam(victim.getUniqueId());
                     if (victimTeam == null || victimTeam.getId() != attackerTeam.getId()) {
                        double multiplier = upgrades.getDamageBonusMultiplier(attackerTeam.getTier());
                        if (!(multiplier <= 1.0)) {
                           double original = event.getDamage();
                           if (!(original <= 0.0)) {
                              event.setDamage(original * multiplier);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private Player resolveAttacker(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player p) {
         return p;
      } else {
         return event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player shooter ? shooter : null;
      }
   }
}
