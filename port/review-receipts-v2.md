# Functional receipt QA — justTeams Fabric

Reviewed current HEAD `f5d6f801ed5050c8583074ad7f36eb3fbc3e2da0`, 2026-10-03 UTC+7. Candidate `build/libs/JustTeams-2.5.5+pxc.1.jar` was independently hashed: SHA-256 `97d20c57ed18261df723cceb82311ac16a2706b62c21f181598d12991ffdfd4d`.

Scope: normal bank/feature state transitions, fence completion accounting, native-chest receipt chaining, full-inventory returns and restart claimability. No server boot, PRD/dev actions, real accounts/player.dat, credentials, licence reads or screen. Application source was not edited. Test helpers and synthetic WALs are confined to `qa/receipt-review/`.

## Findings

No new HIGH/CRITICAL finding in the reviewed normal paths. This is a functional source/synthetic clearance of these paths, not a deployment verdict or a native durability clearance. Failure/concurrency reviews remain independent gates.

## Claims traced and exercised

- **Deposit/withdraw complete only after both legs acknowledge and read back.** `money/BankService.java:122-153`: claim PREPARED by ownerNonce; deposit records PLAYER_INTENT -> confirmed personal debit -> PLAYER_APPLIED -> atomic SQL bank credit; withdrawal performs the SQL debit before PLAYER_INTENT -> confirmed personal credit. Final provider/SQL readbacks precede DONE. `:166-169` returns settled retries without a new provider call. Actual BankService + TeamStore and the repository's unedited `BankServiceTest` JUnit assertion bodies passed **34/34** in independent H2/fake-provider fixtures. These include normal transfer/fees/caps/permissions/idempotency, exact compensation, feature completion/refund children, unknown-outcome freezing and randomized conservation. Evidence rung: **executed/state in synthetic fixtures**; not a real Impactor receipt.

- **Feature settlement drains child receipts.** `money/BankService.java:65-88`: CHARGED is the only unsettled phase eligible for finish; definite refusal writes REFUND_INTENT and confirms the personal refund before the SQL refund. Bank/refund children become DONE before parent DONE/COMPENSATED. Repository assertions check pending=0 for successful/refunded paths and no repeated compensation. TeamStore preserves applied-effect metadata while merging later payloads (`store/TeamStore.java:619-635`).

- **Futures are visible before provider submission and release exactly once.** `money/WalletFence.java:25-33`, `mixin/EconomySaveFenceMixin.java:12-13`: reserve installs a pending ticket before original save; bind observes completion, and synchronous throw invokes fail. `awaitQuiet` waits for all owner/virtual-key pending reservations then rejects failures newer than its checkpoint (`WalletFence.java:43-49`). Actual WalletFence passed **5/5** synthetic checks: reserve visible before bind; successful future releases it; new failed ticket refuses receipt; an old failure does not poison a later confirmed operation; virtual/nonvirtual identities remain separate. Reflection fixture supplies account identity only. This does not establish mixin application, real fsync, canonical-account stability or concurrent writer exclusion.

- **Cursor ownership remains tracked across normal clicks.** `TeamChests.java:88-112`: chest and player/cursor before/after states enter the payload; CHEST_INTENT precedes CAS chest save; PLAYER_INTENT precedes player confirmation; terminal phase is DONE only when cursor is empty. Previous CURSOR_HELD operation is marked DONE after the successor receipt, and a nonempty successor becomes previousOp. Exact current method bodies with actual H2 TeamStore passed cursor -> inventory supersession: inventory=17, confirmed adapter inventory=17, pending=0.

- **Full inventories produce claimable recipient escrow across restart.** `TeamChests.java:119-158`: close journals CLOSE_INTENT; Inventory.add leaves unaccepted remainder in cursorEscrow; clears live cursor; confirms player inventory before ESCROW/DONE. Claim journals CLAIM_INTENT, updates escrow to the remainder and confirms inventory before ESCROW/DONE. Boot exemption is restricted to CHEST/ESCROW (`TeamsCore.java:52`); WAL restore also treats ESCROW as settled custody (`TeamChests.java:192-195`). Exact transaction/finish/claim/append/restoreQuarantine bodies + actual H2 store passed: CURSOR_HELD(64) -> full-inventory ESCROW(64) -> exact current boot predicate -> partial claim ESCROW(54) -> final DONE; final inventory=67, confirmed adapter inventory=67, pending=0, restored freeze sets empty. Thus the old normal-escrow restart trigger in review-money-v1 is closed at source/synthetic level.

- **Player inventory comparison covers Inventory, cursor remains in a separate receipt.** `TeamChests.java:164-168` calls the actual player-save invoker, forces the UUID .dat file and compares Inventory NBT with the captured expected ListTag. It deliberately does not call cursor part of player.dat. Native NBT serialization/readback and PlayerList.save were inspected at source only; helper adapters preserve integer counts and cannot prove Minecraft NBT behavior.

## Reproducible evidence

Run Java21 helper compilation without Gradle/server actions:

```
F:/Pixelclub/tools/.venv/Scripts/python.exe qa/receipt-review/run_review.py
F:/Pixelclub/tools/.venv/Scripts/python.exe qa/receipt-review/chest_review.py
```

Outputs: `qa/receipt-review/test-output.txt`, `chest-output.txt`; hashes of compiled bank/store/fence sources: `source-sha256.txt`. n=34 bank assertion cases, n=5 fence checks, n=2 chest scenarios; window=one completed local run of each helper; interval=one completed case/check/scenario. The helper runs JUnit assertions directly with fresh setup/TempDir rather than using the JUnit engine. It is not native game acceptance. Chest methods are mechanically extracted unedited, but inventory/codec/player-save/menu adapters are synthetic.

## Required native acceptance before a durability claim

1. On an isolated native rig, withdraw to cursor; close with full inventory; inspect ESCROW and unchanged saved inventory; restart; free a partial stack's space; claim twice. Expected remaining receipt count tracks the first claim and DONE follows the final claim, with no pending/frozen row.
2. Pick up a chest stack then move it into player inventory in a second click. Expected predecessor and successor DONE, reopen persists the chest decrement, relog persists the inventory increment.
3. Deposit/withdraw/paid feature with real Impactor hooks; confirm affected account file and SQL state independently, then relog/restart. Exercise unrelated wallet saves and failure injection to validate fence/mixin/global provider behavior.

Verdict: **normal receipt paths pass this review; native persistence and failure acceptance remain unverified.** No balance/refund decision or external action was taken.
