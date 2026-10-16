package com.pixelclub.teams.money;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongFunction;

/**
 * K-1 (+k1a1, Kraus 10-16): how the player's leg of a bank op is asked off the server thread and how the answers of
 * {@code LedgerReceipts.adjustAsync} are read. Every ask of one op uses the same key (the op's {@code ledgerKey}).
 *
 * <p>Fresh op: one attempt (deadline 0). pxc-ledger 0.3.5+ {@code adjust} FAILED means "not applied" and a fresh key
 * has no earlier attempt, so FAILED there is a definite refusal. BUSY / UNSETTLED / unreadable: asked again until
 * definite or {@link #RETRY_DEADLINE_MS}. From the second ask on (and on every later retry) FAILED is NOT "not applied":
 * after an UNSETTLED the ledger answers FAILED for every op on the wallet until its settle, and CANCELLED / INVALID /
 * INACTIVE are decided before the key is looked at. Only the key-checked answers are definite then; anything else keeps
 * the op pending (FROZEN, asked again later with the same key).
 */
final class LedgerAsks {
    private LedgerAsks() {}

    enum Outcome { APPLIED, REFUSED, PENDING }

    static final long FIRST_DEADLINE_MS = 0L;
    static final long RETRY_DEADLINE_MS = 5_000L;

    /** The answer to the first ask of a fresh key. PENDING = ask again. */
    static Outcome first(String answer) {
        return switch (String.valueOf(answer)) {
            case "SUCCESS", "REPLAYED" -> Outcome.APPLIED;
            case "FAILED", "BELOW_FLOOR", "KEY_CONFLICT", "CANCELLED", "INVALID", "INACTIVE" -> Outcome.REFUSED;
            default -> Outcome.PENDING; // BUSY, UNSETTLED, null, EXCEPTION ...
        };
    }

    /** Any answer after an indefinite one, and every retry of a pending op. */
    static Outcome later(String answer) {
        return switch (String.valueOf(answer)) {
            case "SUCCESS", "REPLAYED" -> Outcome.APPLIED;
            case "BELOW_FLOOR", "KEY_CONFLICT" -> Outcome.REFUSED;
            default -> Outcome.PENDING;
        };
    }

    /** {answer, "1"} = decided by the first ask of a fresh key, {answer, "2"} = by a later ask. */
    static Outcome decide(String[] answer) {
        return "1".equals(answer[1]) ? first(answer[0]) : later(answer[0]);
    }

    /**
     * Fresh: the first ask, then (only if it was indefinite) the second. Not fresh (a retry): one ask, read with
     * {@link #later}. {@code ask(deadlineMs)} is the keyed move; its future never fails here (an error reads "EXCEPTION").
     */
    static CompletableFuture<String[]> send(LongFunction<CompletableFuture<String>> ask, boolean fresh) {
        if (!fresh) return safe(ask, RETRY_DEADLINE_MS).thenApply(b -> new String[]{b, "2"});
        return safe(ask, FIRST_DEADLINE_MS).thenCompose(a -> first(a) != Outcome.PENDING
                ? CompletableFuture.completedFuture(new String[]{a, "1"})
                : safe(ask, RETRY_DEADLINE_MS).thenApply(b -> new String[]{b, "2"}));
    }

    static CompletableFuture<String> safe(LongFunction<CompletableFuture<String>> ask, long deadlineMs) {
        CompletableFuture<String> f;
        try { f = ask.apply(deadlineMs); }
        catch (RuntimeException | LinkageError e) { return CompletableFuture.completedFuture("EXCEPTION " + e.getClass().getSimpleName()); }
        if (f == null) return CompletableFuture.completedFuture("EXCEPTION null-future");
        return f.handle((r, e) -> e != null ? "EXCEPTION " + e.getClass().getSimpleName() : r == null ? "EXCEPTION null" : r);
    }
}
