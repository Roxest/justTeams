# justTeams candidate readiness, 2026-10-03

Candidate, not cutover GO. Application source main (after `0347eb3`); SHA256
`65b8a05b08e38efbf142cb5808588c459c458688e30a1f493ad2ae36531f4575`; converter addon
`ca7a0837d812e17b91d512daa544ce5988ad2253a10498b7fd2bdc66ab43cbae`. DEV runs `38a91b9b...` (`7eaaae2`),
server classes identical except openMenu-throw lease cleanup. Build/tests 120 passed. Afternoon round evidence:
[digest-acceptance-20261003.md](digest-acceptance-20261003.md). Earlier candidates (4ad/fa/686/7dd) keep
their own attributions below. No justTeams PRD placement or restart performed.

## Covered evidence

Native own-loopback rig, real Impactor wallet persistence and actual LuckPerms API,
synthetic actors/accounts only. Seven required mixins loaded. Matrix details and failed
harness attempts remain in [runtime-digest.md](runtime-digest.md); QA helpers in `qa/`.
Broad native matrix below is pinned4ad8e0ac; fa1343d0 donor configuration fixes passed
targeted native auth/cap/reload/tier/refund/provider and cold readback. Current6864630f
adds paid chest growth;27->36 with rich stock moved to new slot33/gen17->19/cursor0/pending0
and exact cold readback passed. State evidence does not imply rendered-client evidence.

- Forty randomized decimal transfers conserve personal/team totals and opening.005
  residue; pending0 and independent cold readback. Window05:22:16-05:23:23ICT,
  n40 operations, interval per operation. Existing fees retained.
- Wallet filesystem write failure propagates through explicit and discarded implicit
  saves. Unknown durable outcome freezes; same operation cannot replay. Cold reload
  preserves the prior durable wallet; no automatic refund or monetary reconciliation.
- SQL loss after CHEST PREPARED preserves actor inventory/cursor custody, clears the
  live cursor and lease, quarantines the actor/team, leaves other members' inventories
  intact. Actual vanilla TCP actor login gets quarantine disconnect and EOF.
- Baseline b613 reproduced Creative Inventory.add overflow loss. Final candidate uses
  owned insertion: Creative and Survival full inventory retain ESCROW7, exact cold
  inventory; partial claim1 leaves6, repeat inert, free slot returns6 once. Feature-off
  recipient claim returns already-owned custody. Invalid hotbar SWAP refuses unchanged.
- Cache ALLOW/DENY/UNKNOWN, full-refresh recovery, native PAPI, source role/feature/world
  denials, GUI handlers, decimal join-fee/cap, admin receipts, colours, paid home/cancel,
  cross-dimension warp, and funded invalid create/warp preflights passed covered fixtures.
- Actual79 forward rows,80 reverse rows including reserved synthetic fixture, and rich
  item checksums validate with fixed canonical scientific numbers. These reference
  exports are live/cold_verified=false; they cannot be used for production cold import.

## Remaining acceptance

Closed this round (rung in digest): Upgrades GUI seen on hybrid DEV; GUI-handler purchase/locked state;
chest 36->45->54, shrink retention, SQL-loss custody + cold quarantine readback (state, Carpet, pxc-auth held
out of the rig for that run only); lease release on refused screen; fixture cold lane + rollback with real CLI
and converter (money/members/other tables/untouched chest bytes exact).

1. Operator recovery for a FROZEN chest op does not exist: team stays blocked; the escrow copy must be
   CANCELLED, never returned, when the stored chest still holds the stack. Needs a cold, hash-checked tool or
   written procedure before GO.
2. Host rehearsal of the Docker-gated CLI against a separate catalog (not `pixelclub`) and real MySQL; Kraus
   chose PC fixture only for 2026-10-03.
3. Full target: pure-Fabric bundle/world registry incl. SW6 + nested live End, verified backup + isolated
   restore, fresh cold source on the PRD window, AE/Tools/Spawner/AJ collector gates.
4. Hybrid only: `/team` alias collides with Bukkit vanilla; `/t` proven. Pure-Fabric command surface pending.
5. Owner/CO settings/requests GUI and locked-bank rendering not seen with the member-only DEV profile.
6. Kraus's specific PRD window, then coordinator admission decision.

[CUTOVER.md](CUTOVER.md) is the mechanical cold import/rollback runbook. Cold CLI writes
addon chest rows atomically and refuses unsettled obligations. Rollback preserves latest
legitimate SQL/accounts and completed journals; no old money snapshot overwrite.

Historical review docs retain findings at their original checkpoints. Current receipt
and final-surface fixes are in review-receipts-v2.md/review-final-surfaces-v2.md. Source4d1d7f9
also preserves integer YAML tier keys, atomic six-file reload, finite uncapped/reloaded
bank cap and bounded historical compensation. Focused source refute found no new HIGH/MEDIUM.
No donor values or existing balances were changed.
Source10a879b also expands existing persisted chests to current paid-tier capacity,
preserving all old items and never truncating on configuration decrease. No-op open/
close need not persist empty capacity; handled transaction does, and cold reopen grows safely.
