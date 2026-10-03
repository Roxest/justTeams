# justTeams — PRD cold-window handoff (2026-10-03)

For the restart coordinator. All commands run from Kraus's PC, Git Bash, repo root
`F:/Pixelclub/justteams-fabric-pxc`. **Prefix every command with `MSYS_NO_PATHCONV=1`.**
Nothing here starts or stops a container. Exit 0 prints `justteams_window: OK …`;
exit 9 = HOLD DOWN; any other exit = HOLD DOWN. Evidence lands in
`F:/Pixelclub/scratch/justteams-cutover-20261002/window-prd-<step>-<UTC>/` (private, outside git).

## Rules from the final refute (SHIP, no HIGH)

- Treat **any exit other than 0** as HOLD DOWN (the tool now prints `HOLD DOWN` and exits 9 for every failure).
- **After `import` succeeds, the only way back to the Bukkit plugin is `jt_window.py rollback --target prd`**
  (or dropping `pxc_teams_chests`). Never boot the old plugin and later reinstall the mod without a fresh import:
  stale native rows would resurrect/lose chest items. If `swap` holds after a good `import`, run `rollback`
  before booting Bukkit.
- Never `/reload` or `minecraft:reload` (datapack reload rebuilds the dispatcher without justTeams' nodes).
- Postboot player check is **required** (staff, non-op account): `/team` opens TEAM MENU.

## Preconditions (coordinator)

PRD `pixelclub-arclight` stopped after save-all, `RestartPolicy=no`, no restart between steps.
justTeams steps go after PAPI, before Spawners (your planned order is fine).

## Cold steps, in order

```bash
MSYS_NO_PATHCONV=1 F:/Pixelclub/tools/.venv/Scripts/python.exe tools/jt_window.py import --target prd
```
Dump `pixelclub.donut_*` → off-server (deleted from host) · cold export (`--require-cold`) ·
convert on the PRD-matched rig (226 mods, registry `1988ad94…`) · ColdChests import (addon rows only) ·
verify all 22 donor tables unchanged + native rows = exported rows. Rehearsed on a full PRD copy: **70 s**.

```bash
MSYS_NO_PATHCONV=1 F:/Pixelclub/tools/.venv/Scripts/python.exe tools/jt_window.py swap --target prd
```
Moves `plugins/JustTeams-2.5.5.jar` off-server (archived in evidence), installs
`mods/JustTeams-2.5.5+pxc.1.jar`, creates `config/JustTeams/` from the 7 `plugins/justTeams/*.yml`
byte-for-byte, inserts one pxcnotice rule. Verifies all hashes. Dry-run against live PRD passed. ~10 s.

Worst case added cold time: **~3 min** normal, **8 min** with one converter-rig retry.

## Hashes

| | SHA256 |
|---|---|
| Incoming `mods/JustTeams-2.5.5+pxc.1.jar` | `a35c9c0c662716bbdbcaebd2e2ecd04110d7138edc0017ae2fc63b54b7bbfe61` |
| Outgoing `plugins/JustTeams-2.5.5.jar` | `36c725c1c15afcb546142b9933a40ee14499cffe55daa1d415f9cca7c4bef8af` |
| Converter (PC rig only) | `ca7a0837d812e17b91d512daa544ce5988ad2253a10498b7fd2bdc66ab43cbae` |
| `config/JustTeams/` source | `plugins/justTeams/{config,messages,gui,commands,placeholders,quests,webhooks}.yml` (config.yml `13b9c9cf…`) |

pxcnotice.json: new rule inserted directly after the existing `caller:eu.kotori.justTeams.` rule:
```json
    {
      "match": "caller:com.pixelclub.teams.",
      "label": "TEAM BANK",
      "icon": "minecraft:iron_ingot"
    },
```

## Postboot (after your docker start)

```bash
MSYS_NO_PATHCONV=1 F:/Pixelclub/tools/.venv/Scripts/python.exe tools/jt_window.py postboot --target prd
```
Read-only boot-log check. Must see:
- `[pxc-teams] native 2.5.5+pxc.1 ready: ready=true snapshot-health=true teams=176 members=558 pending=0 … provider=true`
  (teams/members = current counts at the cold dump; the tool checks ready/pending/provider)
- `[pxc-teams] command labels: team=released(vanilla wrapper)->own wrapper | …`
Must be 0: `NOT READY`, `DORMANT`, `watchdog failed`, `stop reconciliation required`.
Required staff check (non-op): `/team` opens TEAM MENU; tag menu shows the team tag (not `%justteams_tag%`).

## Rollback (if postboot HOLD, or Kraus decides later)

Stop PRD (policy no), then:
```bash
MSYS_NO_PATHCONV=1 F:/Pixelclub/tools/.venv/Scripts/python.exe tools/jt_window.py rollback --target prd
```
export-native → reverse convert → ColdChests rollback → restore the archived plugin jar, remove the mod.
Rehearsed on the PRD copy after real play: **65 s**; all donor tables identical to before import except
`player_cache` (last-seen names). Writes made after boot are **kept**: money, members, homes, warps live in the
same donor tables and are never reset; chests saved after cutover are written back as Bukkit blobs; untouched
chests keep their original bytes. Refused (exit 9) while any chest ESCROW/FROZEN is unsettled — players
`/team claim`, or staff `/t admin chestrecover <opid>` (dry-run) then `… apply` with the actor offline.
`config/JustTeams/` and the pxcnotice rule are inert under Bukkit and stay.

## Evidence

`port/digest-acceptance-20261003.md` (afternoon round) plus this round: native on MySQL with the full PRD
copy (176 teams / 558 members / bank Σ 963,937,999.442), non-op `/team` seen, MySQL money round-trip,
real converted 54-stack chest rendered, FROZEN recovery matrix 3/3, backup restore 20/22 exact (2 live tables).
