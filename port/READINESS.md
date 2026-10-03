# justTeams candidate readiness, 2026-10-03

Candidate, not cutover GO. Application source83cdaa4; SHA256
`4ad8e0ac9888c671cc2aabdf98b8d126d2512956edd05c32c48d5171ea1a3f87`.
Immutable local artifact:
`F:/Pixelclub/scratch/justteams-cutover-20261002/artifacts/JustTeams-2.5.5+pxc.1-4ad8e0ac.jar`.
No justTeams PRD/shared-DEV placement or restart performed.

## Covered evidence

Native own-loopback rig, real Impactor wallet persistence and actual LuckPerms API,
synthetic actors/accounts only. Seven required mixins loaded. Matrix details and failed
harness attempts remain in [runtime-digest.md](runtime-digest.md); QA helpers in `qa/`.
State evidence does not imply authenticated real-player or rendered-client evidence.

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

1. Actual pxc-auth unauthenticated/authenticated session; Carpet's explicit loopback
   testActors bypass is not that proof. Actual rendered GUI/text/tag/glow acceptance.
2. TAB-owned outline colour integration that preserves rank prefix/suffix and membership.
   Entity glow flags are tested; no competing scoreboard team should be introduced.
3. Target full bundle on exclusive DEV after Tools releases baseline, current Pack/PAPI/
   auth/LP/world roster; observed12000-tick watchdog with pending0/provider/health.
4. Coordinator current full world/registry manifest including SW6 and nested live End,
   verified backup+isolated restore, fresh cold source, exact private donor settings,
   forward/reverse/cold CLI rehearsals, and coupled AE/Tools/Spawner/AJ collector gates.
5. Kraus's specific PRD restart window and coordinator admission decision.

[CUTOVER.md](CUTOVER.md) is the mechanical cold import/rollback runbook. Cold CLI writes
addon chest rows atomically and refuses unsettled obligations. Rollback preserves latest
legitimate SQL/accounts and completed journals; no old money snapshot overwrite.

Historical review docs retain findings at their original checkpoints. Current receipt
and final-surface fixes are in review-receipts-v2.md/review-final-surfaces-v2.md, with the
right-sort enabled gate and Creative overflow fix applied in source83cdaa4.
