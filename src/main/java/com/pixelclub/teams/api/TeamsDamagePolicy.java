package com.pixelclub.teams.api;

import com.pixelclub.teams.TeamsMod;
import java.util.UUID;

/** Read-only PvP policy. UNKNOWN must not authorize secondary damage. */
public final class TeamsDamagePolicy {
    public enum Decision { ALLOW, DENY, UNKNOWN }
    private TeamsDamagePolicy(){}
    public static Decision evaluate(UUID attacker,UUID victim){var core=TeamsMod.core();return core==null?Decision.UNKNOWN:core.damagePolicy(attacker,victim);}
}
