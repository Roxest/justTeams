# digest — embedded AJ replacement in Skript-fabric

**Source:** `https://github.com/PixelclubCenter/skript-fabric` · clean `F:/Pixelclub/_wt/fabricsk-papi-ready` @ `fc725c62d7e09f6ac66c1e93478209858ad6354b` · 2026-10-02. Read-only discovery; no server/DB changes.

## Identity

FOUND real backend in `expansions/leaderboards/src/main/java/com/demodev/fabricsk/leaderboards/`: `LeaderboardExpansion`, `LeaderboardModule`, `LeaderboardEngine`, `LegacyLeaderboardStore`, `LeaderboardStorage`, `LeaderboardFormat`, `LegacyWriterGate`. SQL primitives are a separate expansion.

Leaderboards tree `049eceb7a1dffd9ba5961653d2dd6640721d3cd6` and SQL tree `f51d5c38f6db1f75b5f7f4ea2e75725f9345a2b8` match migration commit `1be3c59ebc58c942c48da2d2cc21ae59e1e55a2a`. Main checkout @ `a8e8dd5` is older combined SQL/leaderboards.

PRD SFTP pulls, SHA256:

| Artifact | SHA256 | Identity |
|---|---|---|
| `config/fabricsk/expansions/fabricsk-leaderboards.jar` | `1b32323751f9322aea0cd83724b9fcfaa98f2cbe331428dc837fa8e42ca739c2` | expansion id `leaderboards` 1.0.0; entrypoint `com.demodev.fabricsk.leaderboards.LeaderboardExpansion`; dependency `sql` |
| `config/fabricsk/expansions/fabricsk-sql.jar` | `99ad3359ec57c79e8223b91255d2ed735c31c0886d2d6eafaaa382227e324710` | expansion id `sql` 1.0.0; `SqlExpansion` |
| dev combined `fabricsk-sql.jar` | `a11efcdb02d5833c16a57152963b86953d0eb784ed653c056d87dff110f57f89` | older `com.demodev.fabricsk.sql.LeaderboardEngine` |
| base `fabric-sk-1.0.11.jar` | `c96ba78419e34a663f097bfbdf8099cbe751380ed3c64a3c0c7d79f18baf9fe5` | contains no leaderboard classes; base SHA alone cannot pin AJ replacement |

No BUILDINFO. PRD `javap` surface matches split source; byte-exact build provenance NOT established. PRD script SHA `1a714db12ab2df1b2a8914b64dd06a0ff2b300d1e9416fa1e2360ed64374058a` equals both git blobs (local checkout uses CRLF).

## Commands (with real usage)

`migration/scripts/leaderboards/leaderboards.sk`: `/leaderboard`, alias `/lb`, admin `pxc.leaderboards.admin`; status/list/reload, board/player/eligibility management. No usage census. One expansion-load attempt at 23:02:07 UTC+7 is only **executed**.

Read-only `pxcpapi info` reports native owner `fabricsk-leaderboards`. Parser outputs below are **state**, not client **seen**.

## Permissions and nodes

Staff node `pxc.leaderboards.staff`, primary groups or operator excludes; ignore beats include; unknown permissions fail closed. PRD/dev groups empty; actual memberships NOT audited (`LeaderboardModule:217-276`, `LeaderboardEngine:19-25`).

## Config keys (observed values)

PRD: mysql/database `leaderboard`, role display, write/collect false, timezone UTC, sample 10 ticks, cache refresh 200. Dev: same MySQL target, display/write false/collect false. PRD AJ `plugins/ajLeaderboards/config.yml`: `update-stats:true`.

PRD native config SHA `f030471b81894f06e873b1113df2992c19533272faab0a590f940d90b096cfaf`; holograms SHA `edb91a1c454f69b13e30b9aae77e9b11e92317b1165d397133339cdac04fb8e6`. No credentials printed.

## Events listened / cancelled

On-load opens runtime; tick scheduler polls replies/collects at most two player-board pairs; periodic cache/staff verification. Collector uses default Impactor `EconomyService.instance().account(UUID).balance()` and `PLAY_TIME/20` seconds (offline `world/stats/<UUID>.json`). No alternate currency routing or Bukkit cancellation (`LeaderboardModule:222-306`).

## Data owned (exact formats)

Existing MySQL `leaderboard.ajlb_impactor-currency_balance` → currency/impactor; `leaderboard.ajlb_statistic_time_played` → playtime/playtime (`Engine:15-16`). Display validates/reads directly; no import/copy/rename/ALTER.

Layout: UUID `id VARCHAR(36)` PK, `value DECIMAL(65,5)`; hourly/daily/weekly/monthly/yearly each have `_delta`, `_lasttotal`, `_timestamp` numeric fields. `namecache VARCHAR(16)`, prefix/suffix caches 1024, display-name cache 2048. Presentation caches retained. Synthetic value=100.12345/baseline=70/delta=30.12345; timestamps epoch milliseconds. Requires all fields and InnoDB (`LegacyLeaderboardStore:11-46,57-92`).

Monthly=current value minus preserved baseline in same UTC month. Mid-month marker maps to month bucket; original marker retained until rollover. Rollover archives observed delta, then baseline=first new observation; unsampled boundary earnings cannot be reconstructed. Score/eligibility/history DML atomic; DDL not rollback-safe (`Engine:67-83,228-256,303-310`).

New metadata: `fsk_lb_definitions(id,provider,descending_order,table_name,enabled)`; `fsk_lb_overrides(board,uuid,staff,ignored,included,checked_at,sampled_at)` PK(board,uuid); `fsk_lb_history(board,period,period_start,uuid,score LONGTEXT,name,source_start)` PK(board,period,period_start,uuid). Descending exact score, tie UUID ascending; staff filtered.

**SQL state:** host 16:41:00Z/23:41:00 UTC+7 (host/workstation clocks differ). Currency n=3321: sum value `2469241512.68941`, monthly delta `93186511.18602`; playtime n=3318: value `664811721`, monthly delta `11498423`. All markers current October; n=0 delta vs value-minus-baseline disagreements; metadata tables=0. Window=one read-only transaction; interval=one snapshot. Probe `F:/Pixelclub/scratch/papi-prep-20261002/embedded-aj-readonly-aggregate.py`; no player rows leave host.

## Callers and coupling (from the Phase-0 sweep)

PXC seam: `LegacyExpansion.onRequest(PlaceholderSubject,params)` → `LeaderboardEngine.placeholder(params,subject.uuid())`. IDs `leaderboard`/`fabricsklb`, owner `fabricsk-leaderboards`; preferred register when available, older API fallback. NO native `ajlb` alias (`Module:155-193`).

Live holograms: n=40 native monthly tokens, n=0 ajlb, n=20 pixelclubrankings; window=one config read, interval=one snapshot. Mapping ranks 1–10:

- `%ajlb_lb_impactor-currency_balance_<rank>_monthly_name%` → `%leaderboard_currency_<rank>_monthly_name%`
- currency `_value%` → `%leaderboard_currency_<rank>_monthly_value_commas%`
- `%ajlb_lb_statistic_time_played_<rank>_monthly_name%` → `%leaderboard_playtime_<rank>_monthly_name%`
- playtime `_value%` → `%leaderboard_playtime_<rank>_monthly_value_formatted%`

Alias supports same parameters; raw/compact/commas/time formatting exists. Empty name `-`, number `0`, formatted playtime `0s`. Original AJ token names need config migration.

**Parser state:** --null, n=40/namespace, five batches of eight. 23:44:49–23:45:07 UTC+7: all resolve; canonical=alias40/40; native vs AJ32/40 (3 name/5 value mismatches). 23:49:06–23:49:18: native vs AJ40/40 (currency20/playtime20), name sets/values by name identical. Interval=one snapshot each; one differing then one settled point, not transition acceptance. No real names/raw outputs printed.

## Traps

- **Collector gap:** PRD/dev display modes do not collect/write. AJ still observed writer. Existing native pipeline needs exactly one collector enabled after legacy drain. No external collector found in these two configs; other servers NOT inventoried.
- Lease coordinates native collectors only. Gate requires local AJ disabled/update-stats false plus five quiet seconds; remote writers require separate control (`LegacyWriterGate:8-15`).
- Rollback (`migration/LEADERBOARDS.md:218-225`): disable native writes/collection and drain before legacy resumes; retain same rows/metadata/history. NOT rehearsed with real data.

## Not found

BUILDINFO/byteexact build proof; native ajlb alias; collector enabled after AJ removal; external collector census; monthly cold copy; real-data forward/rollback rig; final-bundle transition acceptance; client seen. Rollover/rollback tests exist (`src/test/.../leaderboards/DirectLeaderboardTest:181-197`), NOT run here. Existing XML is old sql package, not split-artifact proof.
