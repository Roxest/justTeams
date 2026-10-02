# Cold chest SQL boundary

`com.pixelclub.teams.tools.ColdChests` runs on host Java 21 without Minecraft or Bukkit.
It copies chest data only: no player account/provider leg, team balances, original journal deletion,
server command or container start/stop. Do not invoke against PRD without the coordinator's approved
cold window and settled writes. This tool does not obtain that window or stop any server.

Use the built patch jar (or compiled `build/classes/java/main`), Gson 2.10.1/2.11.0, and the
exact licensed original Fabric 2.5.5 jar containing the JDBC drivers. MySQL/MariaDB may instead use
their corresponding JDBC driver jar. The original relocated H2 driver is
`eu.kotori.justTeams.libs.h2.Driver`; test H2 2.1.214 is not a replacement for an existing vendor file.
No Fabric loader, registry, provider, world, client, license config or player file is loaded by this CLI.

Keep the Java properties input outside git in a private directory. On POSIX its file must not be
group/world readable (`chmod 600`); outputs are created owner-only. On Windows use a private ACL
for both the properties and artifact directory. The five keys are exactly:

```properties
jdbc_url=<private approved JDBC target>
username=<private user>
password=<private password>
driver=<installed JDBC driver class>
prefix=donut_
```

The program prints a count/hash/generation JSON proof on success and only
`COLD_CHESTS_REFUSED` on failure. It never relays exception text, credentials, raw inventory,
item names, player identities, Docker errors or private paths. Native export files contain the
private inventory strings required by the reverse converter; stdout contains only their file hash.
Every output path uses CREATE_NEW. Existing outputs are refused.

## Docker and fixture gates

Every real invocation requires local `docker inspect --format '{{json .State}}'` for exactly one of
`pixelclub-arclight`, `pixelclub-fabric`, `pixelclub-dev`. Running, Restarting and Paused must all be
actual JSON booleans false. StartedAt must remain unchanged from the first inspection through
pre-commit and final checks. Missing/unavailable Docker, malformed state, a restarted container or
an unallowlisted name is refused. No injected test probe is exposed through CLI arguments.

Tests can use `--fixture-root <absolute local root>` instead of `--container`, only for an existing
`jdbc:h2:file:` database inside that root with the regular `.justteams-synthetic-fixture` marker.
Only original/test H2 drivers and safe MODE=MySQL/AUTO_SERVER=FALSE/DB_CLOSE_ON_EXIT=FALSE/
IFEXISTS=TRUE URL options are accepted. Memory, TCP, MySQL, path escape and INIT-script bypasses
are refused. Fixture mode does not authorize a real deployment target.

## Commands

POSIX classpath separator is `:`; Windows uses `;`. Pin the original and candidate jar hashes in the
coordinator evidence before running. Properties remain in the private file, never command arguments.

```sh
java -cp 'JustTeams-PXC.jar:JustTeams-Fabric-2.5.5.jar:gson-2.10.1.jar' \
  com.pixelclub.teams.tools.ColdChests import \
  --properties /private/teams.properties --container pixelclub-arclight \
  --snapshot /private/donor-cold.json --artifact /private/native-output.json \
  --output /private/import-proof-new.json --apply

java -cp 'JustTeams-PXC.jar:JustTeams-Fabric-2.5.5.jar:gson-2.10.1.jar' \
  com.pixelclub.teams.tools.ColdChests export-native \
  --properties /private/teams.properties --container pixelclub-fabric \
  --output /private/native-cold-new.json

java -cp 'JustTeams-PXC.jar:JustTeams-Fabric-2.5.5.jar:gson-2.10.1.jar' \
  com.pixelclub.teams.tools.ColdChests rollback \
  --properties /private/teams.properties --container pixelclub-fabric \
  --snapshot /private/native-cold-new.json --artifact /private/reverse-output.json \
  --output /private/rollback-proof-new.json --apply
```

Import requires a `cold_verified=true` donor snapshot and the converter's forward artifact whose
source_file_sha256 matches the exact snapshot bytes. Snapshot/artifact rowsets must equal the full
current nonempty donor set. Raw donor blobs and every UTF-8 SHA256 are checked, including at commit.
Format-1 native envelopes receive strict duplicate-key, size/slot/record-shape and canonical checksum
validation. Registry/item/component/SNBT decoding is deliberately not claimed here: retain the
converter's full-registry roundtrip and component proofs from the approved cold snapshot.
After donor schema validation and explicit additive provisioning, TeamStore.importChests performs
the complete batch transaction. Exact generation-1 replay is allowed; changed rows are refused.
Original donor strings are never modified by import.

Export-native captures every addon row with team_id, inventory_json, original_sha256 and generation,
plus format=1, cold_verified=true and snapshot_utc. Use these exact file bytes as reverse-converter
input. The converter must accept generation as input metadata while retaining its source-file hash.
No projection/reformatting can be substituted without changing the bound source hash.

Rollback requires the reverse artifact bound to the exact native export bytes, identical complete
native rowsets, current raw JSON/generation/original hashes, intact original donor UTF-8 hashes and
valid reverse blob/hash pairs. reverse_sha256 is the hash of the exact Base64 text encoded as UTF-8,
not its decoded serialization bytes. Converted originals must still match their old hashes. A null
original hash is accepted only for a new native row with an absent/empty donor row. Reserved synthetic
team 2147483647 is always refused. Team/source/native locks protect validation and all donor
UPDATE/INSERT plus copied addon DELETEs run in one transaction. Completed receipts are retained.
After rollback, replay of the previous nonempty native snapshot is refused.

All modes refuse unresolved money/chest receipts other than invitation/identity metadata, including
normal CHEST ESCROW and cursor/intention states. Settle them through the application/coordinator
first; this tool performs no claim, refund, compensation, repair or journal reset.

An output is reserved before SQL mutation. Failure can leave an empty reserved proof file; it is not
evidence of a commit. SQL transactions are atomic, but Docker state and filesystem proof cannot be
atomically committed with SQL. Any nonzero result after --apply requires coordinator state inspection
before another attempt, including commit-I/O or a concurrent Docker restart. Retain all immutable
donor/native inputs, registry manifests and converter outputs. Never infer database state from the
mere presence of an output or blindly repeat rollback.

## Validation

The isolated suite uses marked H2 files only: import/replay/exact set/hash/shape refusal,
whole-batch rollback, export/reverse restore of converted and new native rows, generation/source/
reverse-hash drift, rollback failure after an earlier write, no replay, ESCROW/bank recovery refusal,
new-output and --apply guards, fixture URL/marker gates, typed Docker/stable-start gates and private
exception-output redaction. Synthetic tests do not inspect a real Docker container or live database.
