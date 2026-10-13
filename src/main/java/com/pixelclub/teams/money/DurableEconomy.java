package com.pixelclub.teams.money;

import java.util.UUID;

/** No account creation, cached Account object, or success from command dispatch. */
public interface DurableEconomy {
    boolean available();
    boolean hasAccount(UUID player) throws Exception;
    double balance(UUID player) throws Exception;
    boolean change(UUID player, double delta, String reason) throws Exception;
    boolean flushConfirmed(UUID player, double expected) throws Exception;
    default String provider() { return "test"; }
    /** true when the last change() on this thread returned false as "definitely not applied" (pxc-ledger keyed move), not "unknown". */
    default boolean definiteRefusals() { return false; }
}
