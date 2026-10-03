# Targeted final surface re-review

Supersedes the financial/admin-kick/sort findings in `review-final-surfaces.md`. Current root WIP, 2026-10-03 ICT. Source root `F:/Pixelclub/justteams-fabric-pxc/src/main/java/com/pixelclub/teams/`. No application edit, server, screen, private config, real player or wallet access. This is a targeted fix review, not another broad sweep.

## Financial fix trace

- `TeamsCore.java:142-147`: create validates raw tag<=255, visible syntax, existing name/raw tag, color<=32 and server-name<=255 before `paid`. Ordinary known conflicts now refuse before any debit; they do not reach `refuse` as SQLException and poison snapshot health.
- `store/TeamStore.java:49,153-154,863-874`: transactional membership/name/tag conflicts use the dedicated `CreateConflict extends SQLException`. Transaction rolls back before rethrow; rollback failure is attached as suppressed. Errors at insert/commit/identity claim still use generic SQL/other exceptions and are not silently reclassified.
- `TeamsCore.java:147,134-140`: only unsuppressed CreateConflict becomes callback=false -> `BankService.finishCharge(false)`; suppressed rollback/close failure escapes to `unknownCharge`. Thus a collision discovered after the preflight can compensate, while an ambiguous storage outcome cannot declare a refund.
- `TeamsCore.java:209-210`: rename fee observes `feature_costs.economy.enabled`, as exact donor TeamCommand:977-978 does. The donor master-feature-cost quirk is intentionally retained; no new issue here.
- `TeamsCore.java:249-257`; `store/TeamStore.java:466-467`: home/warp SQL text lengths are checked before payment. I caught an intermediate warp password mismatch (Core255 vs Store64), reported it, and verified current Core/Store both cap at64. That candidate is closed.

## Executed synthetic proof

Harness: `qa/final-surface-proof/CreateConflictProof.java`. Compiled current real TeamStore/BankService and their dependencies independently with Java21; H2 in-memory donor fixture and a synthetic DurableEconomy wallet. The small callback decision is copied from Core's typed callback; this does not execute a Minecraft ServerPlayer, authentication, or Impactor. No real balances or runtime settings are used.

n=5 cases, window=one local run 2026-10-03 ICT, interval=single execution. Final compile and run exit0 (initial standalone compilation omitted dependency classes; resolved by compiling the actual dependencies; no source fix needed).

```text
PASS name phase=COMPENSATED wallet=100.0 teams=1
PASS tag phase=COMPENSATED wallet=100.0 teams=1
PASS owner phase=COMPENSATED wallet=100.0 teams=1
PASS rollback_unknown phase=FROZEN wallet=75.0 teams=1
PASS sql_unknown phase=FROZEN wallet=75.0 teams=1
```

Each case asserts no half-created team and unchanged membership; compensated cases assert memory/durable wallet conservation, no pending intent and no frozen actor/team. Unknown cases assert retained debit/FROZEN with no automatic refund. This proof would fail the previous generic-create-conflict path.

## Other fixes verified by source

- Admin kick now refreshes the old team before returning (`TeamsCore.java:235`); the combat/PAPI/glow stale-member defect is closed.
- Sort now supplies all three configured selected/unselected lore contexts and trusted rendering (`TeamMenus.java:65,129`); the new right handler uses +2 modulo3, reversing the left +1 (`:70-71`). Prior unresolved-token/forward-only defect is closed.
- **LOW follow-up:** the new right-sort handler is registered unconditionally at `TeamMenus.java:71`, while the normal button respects `items.sort.enabled` inside Screen.button (`:33-34`). If that item is disabled, a right click on its filler slot still changes durable sort. Root was notified. Gate the right handler with the same enabled/action condition. This is an edge config defect, not a financial/auth blocker.
- Prior LOW glow color limitation remains explicit: Core's receiver metadata/outbound patch controls the flag, with outline color depending on the external TAB/scoreboard manager. This review cannot establish configured role/team colors visually.

Verdict: **no remaining HIGH financial defect established in these fixed paths**. Targeted compensation and unknown-outcome handling pass synthetic proof. GUI seen, real Auth, Impactor durability and colored outline remain unverified; do not promote this source/synthetic evidence to PRD acceptance.

## Focused bank-cap refute after 735df8f

Source-only diff review of Settings/Core/BankService/TeamStore and the three added bank tests; no broad matrix, server, application or index edit. Root owns the running build and fixes.

- Parameter ordering is correct in `TeamStore.java:526`: delta, teamId, delta, delta, maxBalance, delta, maxBalance. The cap predicate is `(delta<=0 OR maxBalance<=0 OR balance+delta<=maxBalance)`; withdrawal ignores the positive ceiling, finite nonpositive caps are uncapped, and the preceding locked check rejects nonfinite results (`:518-520`).
- DONE transfer replay occurs before new cap/rights/provider evaluation (`BankService.java:101-105`); applied Store receipts also return before new mutation checks (`TeamStore.java:503-510`). Therefore replay after a lower cap introduces no second money effect.
- Constructor/setCap reject nonfinite caps. setCap and each BankService money method share a monitor; Core.reload and paidAmount share Core's monitor. Ordinary reload cannot change the ceiling halfway through a Core paid operation. It changes no stored balance.
- **MEDIUM — grandfathered above-cap bank balance cannot be restored by a known feature refusal.** `BankService.java:75-82`; `TeamStore.java:519`. Repro: existing bank1005.005, reload cap1000, charge20 bank-first, valid domain callback returns false (e.g. new warp at max capacity). Charge debits to985.005; finishCharge refunds +20 using currentcap1000; Store rejects1005.005 with CAP; parent intent freezes and no feature is delivered. This is a deterministic compensation failure despite stable monitors, not a mid-operation reload race. Root acknowledged and is fixing with a finite historical refund ceiling: positive cap -> max(cap,payload.bankBefore); nonpositive cap stays uncapped. This must still reject concurrent credits that exhaust the original/current ceiling (bankBefore95,cap100,current100,refund10 ->110 refused). Pending targeted regression: above-cap fractional baseline restores exactly, actor wallet unchanged, no pending intent.

Focused verdict at this diff: close the MEDIUM refund edge before acceptance; no other HIGH/MEDIUM defect established in SQL ordering, uncapped semantics, withdrawal, replay, overflow guard or operation locking. Settings atomic-load/integer YAML-key follow-up is outside this narrow refute and remains root's work.
