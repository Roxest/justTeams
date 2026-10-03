# Acceptance round 2026-10-03 (afternoon) — candidate 65b8a05b

HOLD, not GO. Every claim names its rung. Raw evidence stays outside git under
`F:/Pixelclub/scratch/justteams-cutover-20261002/` and the own rig `F:/Pixelclub/justteams-native-rig-20261003/`.

## Pins

| Artifact | SHA256 | Source |
|---|---|---|
| App candidate (final for this round) | `65b8a05b08e38efbf142cb5808588c459c458688e30a1f493ad2ae36531f4575` | see git log after `0347eb3` |
| Superseded same-day candidate | `acd3cdbf5626984388186cf60234947b29d942bfd9a0f7d511bb16b1a74a017b` | `0347eb3` |
| App loaded on DEV + rig acceptance | `38a91b9b2c1117522599cf6ff6785693ad6f454fdd5d6c36e0855e501f03eb64` | `7eaaae2` |
| Chest converter addon (re-pinned) | `ca7a0837d812e17b91d512daa544ce5988ad2253a10498b7fd2bdc66ab43cbae` | `03994dd`+ |
| Superseded converter | `5c2dd4761305e93c91a260b3261e7178ff447de40422777b844e3c1d743ee5c3` | refuses `export-native` rows (no `generation`) |

`38a91b9b`→`65b8a05b` changes the off-server `tools/ColdChests` CLI and one runtime line: `TeamChests.openFor`
also releases the lease when `openMenu` throws (refute LOW). That exception path is source/unit-reviewed only;
the DEV seen and rig state rows below were captured on `38a91b9b`. Immutable copies: `artifacts/JustTeams-2.5.5+pxc.1-{38a91b9b,acd3cdbf,65b8a05b}.jar`,
`artifacts/justteams-chest-converter-2.5.5+pxc.1-ca7a0837.jar`. Build 120/120 tests.

## Code changes this round

- `bde48e7` Upgrades GUI: refuse to open when `team_upgrades.enabled=false` (donor `team_upgrades_disabled`);
  locked button answers `not_owner`. From fresh refute of `7e968ba`: PASS-WITH-NOTES, no HIGH.
- `7eaaae2` Chest lease released when `openMenu` does not open the screen. Before: lease stayed while the
  holder was online, team chest answered `CHEST_IN_USE` to every member (state, own rig: `chests=1`, no viewer).
- `0347eb3` Cold rollback keeps exact original donor bytes for generation-1 imported rows (never saved
  natively); reverse blobs only for changed/new native chests.

## Evidence

| Gate | Rung | Result | n / window |
|---|---|---|---|
| Upgrades GUI tokens | seen (PxcEyes2, DEV hybrid, 38a91b9b) | title `TIER 2/5`; current/next lore, cost 500,000, balance; locked `OWNER ONLY`; click → `[TEAMS] Only the team owner can do that.`; 0 literal `<token>` | n=6 PNG, 12:45–12:48 ICT, `gui-seen-38a9/` |
| Upgrades GUI text | state (own rig, server-built name/lore) | 0 unresolved, owner tier2/tier3 + member-locked | n=3 screens, 11:42–11:43 ICT |
| Upgrade purchase via GUI handlers | state (own rig, Carpet) | member locked click: tier/bank unchanged; owner 2→3 bank 750,000→250,000; 3→4 1,000,000→0 | n=3, 11:42:47–11:43:01 ICT |
| Chest 36→45→54 | state | stack moved 33→44→53, every other slot exact, cursor 0, player inventory exact, gen 19→21→23 | n=2 transitions, 1 rich stack |
| Shrink (all tiers rows=3) | state, cold reboot | persisted 54 kept, tail stack 53→52→53, slot hash back to `dd7c44f0…` | n=2, 11:45:39–11:45:43 ICT |
| SQL loss at 54 | state | lease revoked, tail stack in FROZEN `cursorEscrow`, actor inventory exact, only actor quarantined | n=1, 11:48:31 ICT |
| SQL loss cold readback | state, cold reboot | stored chest unchanged (gen 27, tail stack still stored), member refused `CHEST_RECOVERY_REQUIRED`, pending=1, unrelated inventories exact | n=1, 11:50:26 ICT |
| Lease release on refused screen | state (pxc-auth blocks unauthenticated Carpet screen) | owner + member `STATE_REFUSED`, `chests=0` after each | n=2, 12:50 ICT |
| DEV cold app-only swap 7dd→38a9 | state | COLD_EXIT_0, 267 protected files byte-exact, single start, healthy teams1/members4/pending0 | 12:41–12:43 ICT |
| Fixture cold lane + rollback (real CLI `--fixture-root`, real converter rig) | state | see below | n=1 full run, 13:02:54–13:05:07 ICT |

Rig custody runs used Carpet actors with pxc-auth temporarily held out of the rig (restored after; auth is proven
separately on native TCP and DEV AuthMe). Only one rich stack was in the rig chest: per-slot exactness is
proven for that stack and for all empty slots, not for a full 54-slot inventory.

## Fixture rehearsal (PC, `qa/cold-rehearsal/run.py`)

Marked H2 with all 22 donor tables, 80 synthetic teams/160 members/sub-cent balances and the **exact 79 live
donor chest blobs** (off-server export `8ad76391…`). Cold snapshot read back from the stopped fixture.

- Import: 22 donor tables byte-identical, bank sum unchanged, 79/79 native rows exact vs converter artifact
  at gen 1; exact replay accepted. Forward conversion identical to the audited 00:36 conversion (79/79,
  registry `1988ad94…`).
- Post-cutover legit change: bank +12.345 on one team, that team's chest changed (gen 2), one new native chest.
- Rollback: money equals post-cutover (not reset), members + other 20 tables unchanged, 78/78 untouched chests
  keep exact original bytes, changed chest gets reverse blob, new chest inserted, addon rows 0, second
  rollback refused.
- Measured tool time, final pin 65b8a05b run 13:10–13:12 ICT: forward lane 56 s (convert 52 s incl. rig
  boot/stop, import 0.7 s, readback 2.4 s); rollback lane 55 s (export 0.7 s, reverse 52 s, rollback 0.7 s,
  readback 1.4 s). Earlier acd3cdbf run: 68 s / 61 s. Runner exits non-zero on any false check; replay
  refusal counts only the CLI's own `COLD_CHESTS_REFUSED`.

Not rehearsed: Docker stopped/StartedAt/policy gate, real MySQL/MariaDB, PRD-sized save/stop/boot. Kraus chose
PC-fixture-only today; a host rehearsal needs a separate catalog (CLI refuses dev + `pixelclub`).

## Refute (fresh context, 2026-10-03 13:09)

No HIGH/MEDIUM in code. Lease fix, gen-1 keep-original rule (only import yields gen1 + non-null original;
every save bumps generation) and FROZEN/ESCROW refusal hold. Wording corrections adopted here: owner is
re-checked in `TeamsCore.upgrade`, not in the store; money/members "unchanged after rollback" cannot fail by
construction (rollback never writes them) — only "money not reset" is meaningful; chest rows hold one stack, so
"other slots exact" compared empty slots; the shrink stage did not independently confirm the loaded config;
member direct `/team upgrade` was not tried. GUI/server tier-gap mismatch (>10 or gaps) is display-only, PRD tiers
1..5 contiguous.

## Gaps found

- No operator tool resolves a FROZEN chest op; the team stays blocked (fail-closed, no loss or duplicate seen).
  Resolution is direction-dependent, so "always cancel the escrow" is wrong. The FROZEN payload carries
  `generationBefore/After`, `chestBefore/After`, `playerBefore/After`, `cursorBefore/After`, `sqlCommitUnknown`
  and `quarantinedInventory`/`cursorEscrow`. A cold resolver must: (a) stored chest == (chestBefore,
  generationBefore) → commit absent → actor's rightful state is playerBefore+cursorBefore; (b) stored ==
  (chestAfter, generationAfter) → commit landed → rightful state is playerAfter+cursorAfter (cursor becomes a
  claimable ESCROW once); (c) anything else → refuse. Then compare against quarantinedInventory+cursorEscrow,
  write only the difference as ESCROW (never a second copy), append WAL + SQL terminal phase, and lift the
  quarantine. Today's fault case is (a) with a chest→cursor pickup: the stack is still stored, escrow must be
  CANCELLED. Estimate 2–3 h incl. rig fault matrix (both directions × committed/not).
- Upgrades balance shows `currency_format` (123.005) while root/bank show 123.01 — donor parity, not a bug.
- An abandoned ESCROW (player never runs `/team claim`) also blocks cold rollback; no operator override.
- `/team` alias still collides on hybrid Arclight; `/t` is the hybrid acceptance path.
