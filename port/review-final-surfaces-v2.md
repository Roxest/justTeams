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
