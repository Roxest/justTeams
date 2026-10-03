# Money, chest custody and provider hooks — fresh source review

Baseline: `b70d0ed129beefbd94c37f34266346026785d882`, reviewed 2026-10-03. Findings/line references describe that baseline; the main agent began fixes during review. Scope: bank/provider/fence/mixins, chest/store/destructive/boot/paid paths, commands, actual Impactor 5.3.5 source and bytecode. No PRD/dev, real accounts, live SQL writes, licences or screen.

Intent: native Fabric donor migration while retaining money/item obligations. No new faucet or pricing decision. Refusing unsupported drop actions is smaller than journaling world entities. Converter parity cannot substitute for native menu/failure acceptance.

## Findings requiring fixes

### [CRITICAL] Quarantine storage failure leaves a withdrawn chest stack live

Location: `src/main/java/com/pixelclub/teams/TeamChests.java:101`–`107`; outer handler at `:73`.

Trigger: take a stack from chest to cursor. `saveChest` throws before its commit, then either `operation(op)` or `freezeOperation(op,...)` throws because SQL is still unavailable. The catch handler performs those calls before clearing the cursor, revoking the container and removing the lease. The second exception escapes with `lease.open=true`; the outer handler closes only when it is false.

Effect: the source remains in durable chest SQL and on the usable cursor. After SQL recovers, closing can persist it to player inventory; reopening the durable chest presents the original again.

Executed control-flow proof: `qa/moneyreview/build_repro.py` mechanically extracts the candidate's exact `transaction` method body. Tiny inventory/codec/SQL adapters select the failure branches; no Minecraft click simulation is claimed. Both injected branches printed:

```
failure=freeze-write leaseOpen=true revoked=false cursor=64 durableChest=64 phase=CHEST_INTENT vulnerable=true
failure=operation-read leaseOpen=true revoked=false cursor=64 durableChest=64 phase=CHEST_INTENT vulnerable=true
```

n=2 fault branches, window=one local run, interval=per completed branch. Save/SQL adapters are intentionally fake; the executed code under test is the unedited handler body.

Fix: revoke the lease and detach cursor custody in an unconditional failure/finally path, independent of further SQL success or codec success. Preserve the already durable PREPARED before-state and any successfully written after-state. Do not blind-restore inventory when the chest commit may have succeeded. Retain an in-memory quarantine barrier for the team/player if the persistent FROZEN write cannot be acknowledged; otherwise reopening an unfrozen durable chest can still duplicate the detached live value. Apply the same principle to failed escrow claims, which currently mutate inventory before a fallible freeze call (`:139`–`145`).

### [HIGH] Outside-menu PICKUP still creates an unjournaled world drop

Location: `src/main/java/com/pixelclub/teams/TeamChests.java:68`, mutation at `:88`, completion at `:95`–`99`.

Trigger: pick up a chest stack to the cursor, then left/right-click outside the menu (`slot=-999`, `ClickType.PICKUP`). The guard blocks only THROW.

Primary evidence: pinned Mojmaps MC 1.21.1 `AbstractContainerMenu.doClick` tests -999 at offset 595, calls `Player.drop(carried,true)` at 626 and empties the cursor at 634; secondary drops one item. The candidate confirms only chest SQL/player Inventory, then records DONE without a world receipt. A crash can lose the unsaved entity; failure rollback can duplicate a live dropped entity.

Artifact: `qa/moneyreview/vanilla-drop-bytecode.txt`. Static bytecode/executed inspection only; no native click/state/seen proof.

Fix: refuse outside-slot PICKUP while carrying a stack. Audit the complete allowed vanilla ClickType branches for other world side effects (including SWAP overflow for preserved overstack donor items); permit only actions whose effects are in chest/inventory/cursor custody, or journal and receipt their extra state. Test both mouse buttons, Q/control-Q, hotbar swaps, full inventory and overstack fixtures on the isolated native rig.

### [HIGH] Normal escrow becomes permanently unclaimable after restart

Location: `src/main/java/com/pixelclub/teams/TeamsCore.java:52`; eligibility at `TeamChests.java:136`; freeze gate at `TeamStore.java:541`.

Trigger: close with a nonempty cursor and full inventory. `finish` successfully confirms player.dat, records ESCROW and directs the player to `/team claim` (`TeamChests.java:126`). Restart before claiming. Boot freezes every pending row, including this acknowledged escrow. Claim only accepts ESCROW, so the receipt is now skipped; FROZEN also blocks fresh chest/bank use and destructive membership changes. `/team pending` only lists rows; no operator resolution action is implemented.

Executed H2 proof using the actual seven current TeamStore sources and exact boot loop/claim predicate: `qa/moneyreview/ChestEscrowRestart.java` outputs `claimableBefore=1 claimableAfter=0 phase=FROZEN frozen=true`. n=1 acknowledged escrow, window=one H2 memory run, interval=before/after the boot loop. This proves the phase/authorization failure, not item codec semantics.

Fix: preserve acknowledged ESCROW through boot, or validate its paired receipt and deliberately restore claimability. Continue quarantining CLOSE_INTENT, CLAIM_INTENT, PLAYER_INTENT and other ambiguous phases; do not generalize this exception to financial intents. An escrow recovery design that intentionally requires an operator needs an implemented, reviewable resolution path and player-facing instructions; it cannot promise `/team claim` across restart as presently written.

## Claims traced and remaining limits

- SQL bank leg: `TeamStore.java:370`–`410` locks team/member/op rows, reads current UUID membership and can_withdraw, uses conditional underflow/cap update and records effectApplied with the same transaction. `:526`–`535` retains immutable applied-effect metadata across journal updates. This is source evidence; the bank digest's 34 fake-provider/H2 passes are not actual provider durability evidence.
- Destructive operations: ownership transfer, member removal and team delete consult active financial rows as well as FROZEN. `FINANCIAL_KINDS` at `TeamStore.java:30` excludes CHEST, so it is not a general custody barrier; closure/revocation and the chest findings above remain essential. No blanket claim that all pending obligations prevent new money is supported: `isFrozen` checks FROZEN/UNCERTAIN, while most active phases rely on the one server-thread authority.
- Personal leg: PackEconomy calls the pack's UUID API and reacquires Account through the two-argument cache-backed API; it does not retain Account between calls. It checks provider result, explicit save, fence, disk balance under the real relative I/O lock, and canonical identity outside that lock. The actual pack adapter has internal retries after thrown/FAILED outcomes; those are below BankService's operation-id boundary and need real-provider failure acceptance before an at-most-once personal-leg claim.
- Actual Impactor 5.3.5 `AccountManager.java:27` uses a one-minute cache; `ConfigurateProvider.java:341`–`355` holds its relative-key I/O lock while reading `account.balance()` and swallows save errors. The redirect changes those errors to exceptional save futures; the RETURN hook forces a file after the provider unlocks. These hooks affect every Impactor wallet save, including other mods and virtual accounts, regardless of TeamsCore readiness. They do not assign balances, but they change global error/latency behavior. The hook application, failure propagation, fsync and ordinary unrelated wallet operations need real-provider rig acceptance.
- Fence gap: RETURN registration (`EconomySaveFenceMixin.java:13`) follows asynchronous submission (`EconomyStorage.java:75`, `:105`). awaitQuiet/quiet can miss that enqueue window. `PackEconomy.java:80` checks quiet before a fresh balance lookup, without excluding newly queued writers. No false-success runtime reproduction was run. Register a pending ticket before submission, attach completion, and test paused enqueue/RETURN and stale/canonical instances.
- Across two BankService authorities, baseline's prior lookup and ignored beginOperation return lack a personal-leg claim lock (`BankService.java:96`–`118`). Both can call the provider for one opid. One-core/server-thread execution serializes this locally. Exclude multiple authorities or claim the SQL intent before calling the provider; no multi-server once-only proof exists.
- Duplicate journal keys are rejected (`store/Json.java:72`); NativeChestCodec also parses strictly. Cent normalization/opening residues were traced with no additional HIGH finding. Converter n=79/80 proofs do not cover click/close/disconnect/death/failure custody.

Material choice: whether normal restarted escrow may require an operator. No refunds, balance changes or PRD actions were taken.

Working-tree follow-up: outside-slot PICKUP now refused; boot now preserves ESCROW; BankService now checks an ownerNonce from beginOperation. These close the corresponding static triggers, subject to focused acceptance. Restrict the boot exemption to CHEST/ESCROW. The CRITICAL cleanup proof still reproduces; no native durability clearance is implied.

Verdict: **NO GO — fix before deployment; not ready for PRD.** The failed quarantine path leaves two independently spendable copies of one chest source. After fixes, rerun the focused fault/phase proofs and the isolated native click/cursor/provider failure acceptance; do not infer durability from a final file balance alone.
