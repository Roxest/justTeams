package com.pixelclub.teams.hook;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Completely decoupled, fail-safe reflection hook into Pixelclub Achievements.
 * If pxc-achievements is missing, disabled, or unfinished, this class safely no-ops
 * with zero crashes, zero errors, and zero runtime performance impact.
 */
public final class AchievementHook {
    private static volatile boolean initialized = false;
    private static boolean present = false;
    private static Method incrementMethod = null;
    private static Method setMaxMethod = null;

    private AchievementHook() {}

    private static synchronized void init() {
        if (initialized) return;
        try {
            Class<?> apiClass = Class.forName("com.pixelclub.achievement.api.PxcAchievementApi");
            incrementMethod = apiClass.getMethod("increment", UUID.class, String.class, int.class);
            setMaxMethod = apiClass.getMethod("setMax", UUID.class, String.class, int.class);
            present = true;
        } catch (Throwable ignored) {
            present = false;
            incrementMethod = null;
            setMaxMethod = null;
        } finally {
            initialized = true;
        }
    }

    /**
     * Increments achievement progress. Safely no-ops if achievement mod is not present.
     */
    public static void increment(UUID playerUuid, String achievementId, int amount) {
        if (playerUuid == null || achievementId == null) return;
        if (!initialized) init();
        if (!present || incrementMethod == null) return;

        try {
            incrementMethod.invoke(null, playerUuid, achievementId, amount);
        } catch (Throwable ignored) {
            // Never allow an achievement error to interrupt team logic
        }
    }

    /**
     * Sets maximum achievement progress. Safely no-ops if achievement mod is not present.
     */
    public static void setMax(UUID playerUuid, String achievementId, int value) {
        if (playerUuid == null || achievementId == null) return;
        if (!initialized) init();
        if (!present || setMaxMethod == null) return;

        try {
            setMaxMethod.invoke(null, playerUuid, achievementId, value);
        } catch (Throwable ignored) {
            // Never allow an achievement error to interrupt team logic
        }
    }

    /**
     * Checks if the achievement mod is actively detected and available.
     */
    public static boolean isAvailable() {
        if (!initialized) init();
        return present;
    }
}
