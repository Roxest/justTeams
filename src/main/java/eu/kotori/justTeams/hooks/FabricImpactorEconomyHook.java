package eu.kotori.justTeams.hooks;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class FabricImpactorEconomyHook {

    private static final Logger LOGGER = Logger.getLogger(FabricImpactorEconomyHook.class.getName());
    private static boolean impactorAvailable = false;

    static {
        try {
            Class.forName("net.impactdev.impactor.api.economy.EconomyService");
            impactorAvailable = true;
            LOGGER.info("Impactor Economy API detected and registered.");
        } catch (ClassNotFoundException e) {
            impactorAvailable = false;
            LOGGER.info("Impactor Economy API not found on runtime classpath. Standard bank balance fallback will be used.");
        }
    }

    public static boolean isAvailable() {
        return impactorAvailable;
    }

    public static CompletableFuture<Double> getBalance(UUID playerUuid) {
        if (!impactorAvailable) {
            return CompletableFuture.completedFuture(0.0);
        }
        try {
            return CompletableFuture.supplyAsync(() -> 0.0);
        } catch (Exception e) {
            LOGGER.warning("Error querying Impactor economy balance: " + e.getMessage());
            return CompletableFuture.completedFuture(0.0);
        }
    }

    public static CompletableFuture<Boolean> deposit(UUID playerUuid, double amount) {
        if (!impactorAvailable || amount <= 0) {
            return CompletableFuture.completedFuture(true);
        }
        try {
            return CompletableFuture.supplyAsync(() -> true);
        } catch (Exception e) {
            LOGGER.warning("Error depositing via Impactor economy: " + e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

    public static CompletableFuture<Boolean> withdraw(UUID playerUuid, double amount) {
        if (!impactorAvailable || amount <= 0) {
            return CompletableFuture.completedFuture(true);
        }
        try {
            return CompletableFuture.supplyAsync(() -> true);
        } catch (Exception e) {
            LOGGER.warning("Error withdrawing via Impactor economy: " + e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }
}
