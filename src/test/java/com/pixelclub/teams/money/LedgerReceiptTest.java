package com.pixelclub.teams.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

/** The keyed ledger move's outcome rule (+xs5, rig K1 2026-10-13 fault run). */
class LedgerReceiptTest {
    static class Script implements LedgerReceipt.Ledger {
        final Deque<String> answers;
        final Deque<Boolean> unsettled = new ArrayDeque<>();
        int asks, settleChecks;
        BigDecimal lastFloor;
        Script(String... a) { answers = new ArrayDeque<>(Arrays.asList(a)); }
        @Override public String adjust(BigDecimal delta, BigDecimal floor) { asks++; lastFloor = floor; return answers.removeFirst(); }
        @Override public boolean unsettled() { settleChecks++; return !unsettled.isEmpty() && unsettled.removeFirst(); }
    }

    @Test void successAndReplayedAreApplied() throws Exception {
        assertTrue(LedgerReceipt.adjust(new Script("SUCCESS"), -100, "k", 100));
        assertTrue(LedgerReceipt.adjust(new Script("REPLAYED"), -100, "k", 100));
    }

    @Test void aFirstRefusalIsDefinite() throws Exception {
        Script s = new Script("FAILED");
        assertFalse(LedgerReceipt.adjust(s, -100, "k", 100));
        assertEquals(1, s.asks);
        assertEquals(0, BigDecimal.ZERO.compareTo(s.lastFloor), "a withdraw is floored at zero");
        assertNull(new Script("SUCCESS").lastFloor);
    }

    @Test void unsettledThenReplayedIsAppliedOnceAfterSettling() throws Exception {
        Script s = new Script("UNSETTLED", "REPLAYED");
        s.unsettled.add(true); s.unsettled.add(true); s.unsettled.add(false);
        assertTrue(LedgerReceipt.adjust(s, -100, "k", 1_000));
        assertEquals(2, s.asks);
        assertTrue(s.settleChecks >= 3, "waited for the wallet to settle before asking again");
    }

    @Test void aRefusalAfterUnsettledIsNotTrusted() {
        // the fault run: held COMMIT -> UNSETTLED, breaker open -> FAILED: the earlier COMMIT may still land
        Script s = new Script("UNSETTLED", "FAILED", "FAILED");
        assertThrows(IllegalStateException.class, () -> LedgerReceipt.adjust(s, -100, "k", 10));
        assertEquals(3, s.asks);
    }

    @Test void unsettledThenFailedThenSuccessIsApplied() throws Exception {
        assertTrue(LedgerReceipt.adjust(new Script("UNSETTLED", "FAILED", "SUCCESS"), -100, "k", 10));
    }

    @Test void busyOnlyIsNotApplied() throws Exception {
        assertFalse(LedgerReceipt.adjust(new Script("BUSY", "BUSY", "BUSY"), 100, "k", 10));
    }

    @Test void stillUnsettledPastTheBudgetFreezesWithinIt() {
        // scrutinize MED-4: the total wait is bounded (server thread)
        Script s = new Script("UNSETTLED", "SUCCESS") {
            @Override public boolean unsettled() { settleChecks++; return true; }
        };
        long t0 = System.nanoTime();
        assertThrows(IllegalStateException.class, () -> LedgerReceipt.adjust(s, -100, "k", 200));
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(ms < 600, "bounded by the budget, took " + ms + " ms");
        assertEquals(1, s.asks, "never re-asked while still unsettled");
    }

    @Test void anInterruptFreezesAndKeepsTheFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> LedgerReceipt.adjust(new Script("BUSY", "SUCCESS"), 100, "k", 1_000));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept for the caller");
        } finally {
            Thread.interrupted();
        }
    }

    @Test void keyConflictThrows() {
        assertThrows(IllegalStateException.class, () -> LedgerReceipt.adjust(new Script("KEY_CONFLICT"), 100, "k", 10));
    }
}
