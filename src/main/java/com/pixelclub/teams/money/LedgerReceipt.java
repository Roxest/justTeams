package com.pixelclub.teams.money;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The durable receipt when pxc-ledger (the shared MySQL wallet behind Velocity) is the economy, reached by reflection.
 * Under the ledger Impactor's wallet file is never written, so {@link PackEconomy#flushConfirmed}'s file read-back
 * would fail and freeze every bank op after the player's money had moved. A ledger SUCCESS already means the change is
 * committed in MySQL; the receipt is a committed read (not the cache) equal to what the op expects, on a wallet with no
 * unsettled change.
 */
final class LedgerReceipt {
    private static final String API = "com.pixelclub.ledger.api.LedgerReceipts";

    private LedgerReceipt() {}

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        try {
            return Class.forName(API).getMethod(name, types).invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception x ? x : e;
        }
    }

    /** pxc-ledger is the economy on this server (false when it is absent, disabled or fail-closed). */
    static boolean active() {
        try {
            return Boolean.TRUE.equals(call("active", new Class<?>[0]));
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /** The committed balance of the primary currency equals {@code expected}, and nothing on the wallet is unsettled. */
    static boolean confirmed(UUID player, double expected) throws Exception {
        String key = primaryKey();
        if (Boolean.TRUE.equals(call("unsettled", new Class<?>[]{UUID.class, String.class}, player, key))) return false;
        BigDecimal committed = (BigDecimal) call("committed", new Class<?>[]{UUID.class, String.class}, player, key);
        return PackEconomy.same(committed.doubleValue(), expected);
    }

    private static String primaryKey() throws Exception {
        Class<?> api = Class.forName("net.impactdev.impactor.api.economy.EconomyService");
        Object service = api.getMethod("instance").invoke(null);
        Object currencies = api.getMethod("currencies").invoke(service);
        Object primary = Class.forName("net.impactdev.impactor.api.economy.currency.CurrencyProvider").getMethod("primary").invoke(currencies);
        Object key = Class.forName("net.impactdev.impactor.api.economy.currency.Currency").getMethod("key").invoke(primary);
        return (String) Class.forName("net.kyori.adventure.key.Key").getMethod("asString").invoke(key);
    }
}
