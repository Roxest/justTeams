# Bank acceptance tests

Scope: `BankServiceTest.java` only, rooted at store commit `9b1f041`. Evidence rung: **state** from isolated H2 memory SQL readback and the fake provider's separate memory/persisted balances. No server boot, runtime configuration, donor DB, real account, licence key, dev or PRD access.

## Contract exercised

- Deposits conserve durable player plus SQL bank; withdrawals subtract only the configured fee. `0.005` rounds once to `0.01`; opening DOUBLE subcent residues remain.
- Withdrawal uses `can_withdraw` independently of OWNER/MEMBER/CO_OWNER role. Bypass retains underflow checks. Cap, insufficient funds, NaN/infinity/negative/rounded-zero input and missing account fail before provider writes.
- Settled same-op retry and actor/amount/direction collisions make no further provider calls. Ambiguous provider false/exception after a durable effect and rejected persistence freeze without replay.
- A fixture-only concurrent bank cap change forces a definite SQL rejection after debit, followed by a confirmed refund; a failed refund is frozen. Journal and WAL failures after the first leg preserve the obligation and never blindly repeat.
- A newly constructed service retains frozen/pending state with no automatic payout. Feature costs use bank first (preserving residues) or personal only, retain ambiguous actions, and compensate definite action failure with persistence confirmation. Active financial work blocks leave/kick/ownership transfer/team deletion.
- A deterministic serial random sequence has n=80 operations, plus cap and permission negative controls; durable total and SQL balance are read after every step. This is a correctness fixture, not a benchmark.

## Validation and finding

Selected Java 21 compilation uses the seven committed store classes, three read-only copies of the main worktree money sources, H2 2.1.214, Gson 2.13.2 and cached JUnit 5.11.4/Platform 1.11.4. Copies, classes and a tiny launcher are under ignored `build/bank-acceptance/`; no main files are committed by this branch. Full Gradle is deferred while the main application is in progress. After merge: `./gradlew test --tests com.pixelclub.teams.money.BankServiceTest`.

Final n=34, window=one isolated run (1.446 seconds), interval=per completed test: **34 passed, zero failed**, after the main agent's settlement fix. Both the original refund-exception reproduction and the added SQL-refund-journal failure passed focused runs. The final BankService compile input matches the main worktree byte-for-byte, SHA256 `0014225e6773981ed468264ded06ed39fdfbf6fbe4b11206e61caf5e7dde3e87`.

Initial n=31, window=one isolated run (1.312 seconds), interval=per completed test: 30 passed, one failed. `featureRefundExceptionRetainsFrozenObligationAndCannotRepeat` reproduced bank 10/player 100 → confirmed charge bank 0/player durable 90; the refund provider changes/persists player 100 and throws. The old `finishCharge` propagated and left the journal `REFUND_INTENT`. The main agent added settlement exception quarantine: the final test observes `FROZEN`, no retry effect, and fresh financial work refused. No production money implementation was changed by the test worker.

Additional compensation tests verify SQL journal failure after the confirmed player refund preserves the remaining bank obligation, WAL failure after restored balances remains quarantined, and a bank-refund cap rejection never claims compensation. An initial SQL fault-injection constraint rejected existing applied rows; it was narrowed to the refund child operation and rerun.

The main agent explicitly defined terminal acknowledgment as idempotent for the same action outcome: repeated DONE/true and COMPENSATED/false acknowledge true, opposite outcomes refuse, and neither produces further provider calls or effects. Tests cover that decided contract.
