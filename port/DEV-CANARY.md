# DEV GUI canary — prepared, not deployed

Coordinator owns the released DEV slot. Worker made no remote mutations. No PRD
watcher/stop is armed or mutative window exists, per coordinator.

Private owner-only kit:
`F:/Pixelclub/scratch/justteams-cutover-20261002/private-dev-canary-20261003`

Review `preflight-private.json`, `fixture-proof.json` and
`baseline-refresh-private.json`. `deploy/` contains only the native
686 jar, six sanitized donor YAML and a fresh marked synthetic H2 file.
`bootstrap/` and `rollback/` are off-server only; never put their original jar
into canary mods and never boot the private rollback configuration on a rig.

Identity pins:

- Current DEV rollback `mods/justTeams-2.5.5.jar` SHA256
  `002a0e76b9695f8f029cdd4b080c68f8cf2fac7e7476b122524b406742347323`.
  ZIP id `justteams`, version `2.5.5`, entrypoint
  `eu.kotori.justTeams.JustTeamsFabric`; 19 donor storage classes, zero native
  classes. Mtime precedes Tools StartedAt; loaded SHA is unknown.
- Native app source `10a879b`, SHA256
  `6864630f29a2b33cc0299e3cfcbca4335a1916e459c8208b8460165835567487`.
- Original standalone driver/build input only, SHA256
  `162dfd8c551497d08fcfe64dbbcc09437812a8ca1ae3eddf442408e6cf72e86a`.
  This is not the current DEV rollback identity. PRD Bukkit original remains
  `36c725c1c15afcb546142b9933a40ee14499cffe55daa1d415f9cca7c4bef8af`.
- Sanitized config SHA256
  `f7f8d7b908162d0aecf600f17ce51b4f09c2ac9a562375cf8febb99cf21e1a84`:
  storage H2/prefix donut_, Redis disabled; donor prices, integer tiers and
  behavior preserved. Other five YAML retain exact donor bytes. Current DEV
  six originals are separately backed up, with SHA/type metadata privately.
- Fresh H2 SHA256
  `2e79a569fb5e09f4b660819bbcfac65cd2c2ee70c315259319ed9d5aef06ff28`.
  Schema22; only JTMoneyOwner/JTMoneyMember/JTMoneyCo/PxcEyes2 offline UUIDs;
  pending0; tier2; opening bank123.005; rich chest36/gen1. PxcEyes2 is MEMBER,
  can_use_enderchest=true/can_withdraw=false, UUID
  `42bb1850-a13a-35e8-aa40-c9d2bb34a1ae`. No wallet/auth/player/world/license or
  runtime bank/chest WAL was copied. ColdFiles shape/hash validation passed;
  this preparation did not execute Minecraft item decoding or render a GUI.

Standalone bootstrap was executed with host Java21:
`C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin/java.exe`.
Classpath: `bootstrap/classes`, `deploy/mods/JustTeams-2.5.5+pxc.1.jar`,
`bootstrap/JustTeams-Fabric-2.5.5.jar`, `bootstrap/gson-2.10.1.jar`.
`FixtureBootstrap <kit>/deploy` created the file; then
`com.pixelclub.teams.tools.CanaryFixture` added the fourth member via actual
686 Store APIs and seeded only the synthetic rich fixture. These commands
refuse existing target/proof; use the prepared file.

Immediately before coordinator applies the lease, repeat read-only list/state
and mod/config identity checks. Require player0 and unchanged DEV StartedAt
`2026-10-03T01:52:09.51251204Z`/PID3724316; Tools baseline a7092a77 and Donut
565a0b04 remain pinned. Container policy currently unless-stopped; coordinator
must own its explicit cold window and restart-policy handling. Docker state
does not prove a JDBC target: the canary uses only marked synthetic H2, never
shared MySQL pixelclub. Preserve any existing target file before placing the
new file. Stage only while cold, without overwriting any running-JVM jar.

Coordinator validates loaded status, PxcEyes2 UUID, permission denials, pending0
and donor-rendered GUI using the approved client/genuine DEV PxcAC context.
Own-rig1.18.8/1.18.9 failed crypto; matching versions alone proves no handshake.
No new client mod, private-key copy or policy bypass is part of this canary.
Rollback restores exact002a jar and exact DEV six files in coordinator's cold
window, retaining preexisting DEV data; this synthetic canary performs no
money test/grant/purchase against real actors. The complete cutover remains
governed by CUTOVER.md and READINESS.md; this is no production GO claim.
