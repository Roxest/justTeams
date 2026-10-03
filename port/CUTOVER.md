# justTeams cold cutover / rollback handoff

NOT GO. Based on the 2026-10-03 living note `00-now/justTeams Fabric cutover — ทำต่อจากตรงนี้.md`.
Source/artifact readiness is separate from placement: **no justTeams PRD placement done**.
GUI may still change; pin final source/overlay/converter/full-bundle hashes together.
Latest application candidate SHA256: **fa1343d04899f00e0a231ab0a78eaeccfb41e395043b3b379afdba90b309c9ed**,
application source4d1d7f9, private prereleasev2.5.5-pxc.1-rc1 (remote digest verified).
Immutable artifact:
`F:/Pixelclub/scratch/justteams-cutover-20261002/artifacts/JustTeams-2.5.5+pxc.1-fa1343d0.jar`.
Broad native matrix/auth passed prior4ad; final candidate targeted acceptance still pending.
This document authorizes no server change.

Immutable loader-specific originals, retained outside every server directory:

- Original DEV Fabric2.5.5: `162dfd8c551497d08fcfe64dbbcc09437812a8ca1ae3eddf442408e6cf72e86a`.
- PRD donor Bukkit2.5.5: `36c725c1c15afcb546142b9933a40ee14499cffe55daa1d415f9cca7c4bef8af`.
- Existing live forward79/2376slots/584items and reverse80/rich-fixture proofs are reference only,
  cold_verified=false. Reserved fixture team2147483647 never enters a live manifest/import.

## Coordinator gates, before any shared environment work

Record owner, target/container/DB/prefix, final hashes and explicit Kraus authorization for THIS
PRD stop/start/restart window. Deploy approval is not restart approval. Hold whitelist until green;
record rollback decision time. No money/refund/price/system-removal/client change is implicit.

Tools primary fbaed118...c9f0/source7c57f97 is Kraus-selected; obtain full SHA/owner release.
AE/Spawner/PAPI collector-monthly/worlds remain owner HOLD. PAPI released shared DEV04:12ICT;
Tools holds the following window until its explicit baseline/owner handoff. Recheck current owner;
no overlapping restart/deploy. Finish DEV before PRD, never during PRD work.

Require actual full mod/library/nested-JiJ roster, MC1.21.1/loader/provider/auth/permissions versions,
item/component registry and current world manifest. Include live SW6, preserve SW5 homes/RTP;
world owner must resolve exact nested liveEnd source/dimension paths/hashes. Never guess/flatten
DIM1 or use obsolete sparse fixtures. Bind homes/warps/player positions to loaded IDs/Bukkit aliases.

Require private verified backup AND isolated restore proof with own licenses/no real-player access:
world/dimension/player data, SQL/accounts/WAL/config/original jars and post-switch backup path.
Backup existence alone is insufficient. Missing restore/registry/world/peer clearance stops the bundle.

## Private settings and fresh cold source

Copy exact donor config/messages/gui/commands/placeholders/quests `.yml` into uppercase
`config/JustTeams`; preserve prices/caps/fees/tiers/join/rename/colors/actions/permissions/features/
world restrictions/disabled quests. No scaffold defaults or lowercase-folder substitution.
Config/credentials/licenses/accounts/raw items stay private, never git. Isolated DEV uses H2,
synthetic wallets/own licenses; PRD retains original MySQL/economics. No PRD keys on a rig.
Keep opening DOUBLE/sub-cents, IDs/UUIDs, ten flags/roles and legacy SQL invite existence semantics.

In the exclusive window settle BANK/CHEST/provider saves, close viewers and return/claim cursor.
Resolve ALL unsettled receipts including ESCROW/FROZEN; unknown stops work, no blind retry/refund.
Drain async writes; coordinator stops correct JVM and pins RestartPolicy.Name=`no`, retaining prior
policy for authorized restore. Helper never changes it. Running/Restarting/Paused=false and same
StartedAt before/through/after SQL; exclude concurrent writer/start actions.

Docker does NOT bind JDBC. DEV/PRD may share MySQL/MariaDB `pixelclub`; CLI refuses that catalog
with pixelclub-arclight-dev. Coordinator proves host/DB/prefix and excludes all writers. Allowlist:
pixelclub-arclight, planned pixelclub-fabric, pixelclub-arclight-dev; no pixelclub-dev alias.

Collect CURRENT cold source/config/GUI/message/jar/world hashes, all 22 schemas/row counts,
IDs/UUIDs/roles/flags/balance projection/raw hashes, accounts and chest rows. Fresh count must
confirm79 or explain change; never truncate to live79. Retain immutable byte SHA/UTC/cold=true.
Forward-convert CURRENT cold rows on exact full registry; verify every slot/item/count/typed
component, reverse roundtrip and pure-Fabric registry proof with equal full rowsets/hashes.
Failed/partial conversion or registry proof => **no native boot**.

## Host Java21 SQL commands: prepared, not executed by this document

Verify host `java -version` major21. Artifacts stay off-server. Exact POSIX CP below: final overlay,
original Fabric driver jar, Gson2.10.1 (Windows separator `;`). Private owner-only properties keys:
jdbc_url/username/password/driver/prefix; never echo/commit. Every output must be new.

```sh
JT_CP='/private/cutover/JustTeams-PXC-FINAL.jar:/private/cutover/JustTeams-Fabric-2.5.5.jar:/private/cutover/gson-2.10.1.jar'
java -cp "$JT_CP" com.pixelclub.teams.tools.ColdChests import --properties /private/cutover/teams-prd.properties --container pixelclub-arclight --snapshot /private/cutover/COLD/donor.json --artifact /private/cutover/COLD/native-output.json --output /private/cutover/COLD/import-proof-new.json --apply
```

Forward source_file_sha256 binds EXACT cold snapshot bytes; original_sha256 binds blob UTF8.
CLI validates 22 tables/full nonempty set/checksums; atomic import writes only addon rows, never
rewrites22 originals. Generation1 replay must match format/payload/hash. Keep original blobs,
all hashes/registry proofs; stdout has no contents.

Stage only with stopped JVM; never overwrite a running jar or leave server backups. Keep originals
off-server. Coordinator starts pinned candidate once; loaded alone never enables player admission.

## Postboot evidence, then coordinator decision

- `/team admin status`: ready/snapshot-health/provider=true, pending=0, CURRENT cold counts;
  pinned boot/mixins/version. Verify aliases and native PAPI `%justteams_tag%`/display gate.
- Real auth/login: pre-auth denial, member/elevated stored-withdraw=false denial, explicit bypass,
  owner protection and fresh GUI/context permissions.
- Approved synthetic rig/DEV account: n=1 small deposit/withdraw, durable player+SQL conservation
  minus original fees, sub-cents/replay/pending checked. No real players or balance grants. PRD
  postboot is read-only unless Kraus explicitly authorizes a scoped synthetic exception.
- Home/warp dimension and cross-world alias/load checks use current SW6/liveEnd mappings. Chest
  full items/CAS/one-viewer/cursor/ESCROW/claim receipts and player.dat persistence must survive
  clean stop/second boot without duplication, loss or unquarantined unknown outcomes.
- Observe actual 12000-tick watchdog: ready/pending/open-chests/provider; status snapshot-health=true,
  pending=0 after close. Record n/window/interval, no invented wall-clock cadence. Rendered GUI/
  text/tag/glow needs authorized eyes window while Kraus is not using PC; dispatch is not seen.

Only the master coordinator/Kraus clears all gates, admits players and restores restart policy.
If any fails, keep entry blocked and preserve receipts/evidence; this document never declares GO.

## Cold rollback preserving legitimate post-cutover state

Obtain the specific rollback stop/start authorization, drain/close/settle, pin policy no and verify
the same cold guards. Never overwrite the latest SQL/accounts with pre-cutover snapshots: preserve
legitimate money/roles/quests/schema/journal changes. Resolve all obligations, including ESCROW.

```sh
java -cp "$JT_CP" com.pixelclub.teams.tools.ColdChests export-native --properties /private/cutover/teams-prd.properties --container pixelclub-fabric --output /private/cutover/ROLLBACK/native-latest-new.json
java -cp "$JT_CP" com.pixelclub.teams.tools.ColdChests rollback --properties /private/cutover/teams-prd.properties --container pixelclub-fabric --snapshot /private/cutover/ROLLBACK/native-latest-new.json --artifact /private/cutover/ROLLBACK/reverse-output.json --output /private/cutover/ROLLBACK/rollback-proof-new.json --apply
```

Between commands reverse-convert EXACT latest export (generation retained) on full original registry;
prove native->Bukkit->native slots/components. Reverse source_file_sha256 binds export bytes;
reverse_sha256 hashes reverse_blob UTF8 TEXT, not decoded Base64. Require exact current JSON/
generation/original hashes/full sets. Null originals need new native+absent/empty donor.
Atomic donor UPDATE/INSERT+addon DELETE preserves completed journals/money/accounts/schema; no replay.

Cold restore loader-specific ORIGINAL from off-server copy: DEV Fabric162dfd...; PRD Bukkit36c725...
requires original approved Arclight/world/auth bundle, never a Bukkit plugin on pure Fabric.
Recheck invariants/boot/readback/whitelist; retain addon schema/audit.
Nonzero --apply may mean SQL commit-I/O UNKNOWN, not definite rollback; empty proof proves nothing.
Coordinator inspects before retry: no blind repeat/snapshot overwrite/refund/old monetary restore.
