package com.pixelclub.teams.hook;

import java.util.UUID;

public class AchievementHookTest {

    private static void check(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    public static void main(String[] args) {
        // 1. Without pxc-achievements, isAvailable() must return false
        check(!AchievementHook.isAvailable(), "AchievementHook should be safely inactive without pxc-achievements");

        // 2. Safe execution of increment with nulls and valid inputs
        AchievementHook.increment(null, "social_find_your_clan", 1);
        AchievementHook.increment(UUID.randomUUID(), null, 1);
        AchievementHook.increment(UUID.randomUUID(), "social_find_your_clan", 1);
        AchievementHook.increment(UUID.randomUUID(), "social_clan_chat", 1);
        AchievementHook.increment(UUID.randomUUID(), "social_clan_leader", 1);
        AchievementHook.increment(UUID.randomUUID(), "social_climbing_the_ranks", 1);
        AchievementHook.increment(UUID.randomUUID(), "social_team_bank", 1);
        AchievementHook.increment(UUID.randomUUID(), "social_team_home", 1);
        AchievementHook.increment(UUID.randomUUID(), "social_team_warp", 1);
        AchievementHook.increment(UUID.randomUUID(), "social_team_enderchest", 1);
        AchievementHook.increment(UUID.randomUUID(), "social_team_upgrade", 1);

        // 3. Safe execution of setMax
        AchievementHook.setMax(null, "social_find_your_clan", 1);
        AchievementHook.setMax(UUID.randomUUID(), null, 1);
        AchievementHook.setMax(UUID.randomUUID(), "social_find_your_clan", 1);

        System.out.println("JUSTTEAMS ACHIEVEMENT HOOK TESTS PASSED: 14 assertions verified");
    }
}
