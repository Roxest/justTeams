# Cold migration and snapshot QA

Cold source: commit `8f2b70d588948627d113ddf40955fce75a2d2e81`, `F:/Pixelclub/_wt/justteams-store`. Snapshot source: current `F:/Pixelclub/justteams-fabric-pxc/src/main/java/com/pixelclub/teams/TeamSnapshots.java` and its consumers. Reviewed 2026-10-03 UTC+7. No server/Docker invocation, real SQL, credentials, licence/player data or screen. No application source edits. Only this report was added to the main checkout; compiled classes and H2 fixtures used fresh system-temp directories.

## Actionable defects

### [HIGH] Docker allowlist excludes the configured real dev container

Location: worktree `src/main/java/com/pixelclub/teams/tools/ColdGate.java:15`, `:24-26`; `tools/cold-chests/README.md:34-37`.

`CONTAINERS` includes `pixelclub-dev`, while the current operational tool `F:/Pixelclub/tools/pxc.py:49-53` declares dev as `pixelclub-arclight-dev` and PRD as `pixelclub-arclight`. The skill's server-access table independently uses `pixelclub-arclight-dev`. Therefore a cold invocation using the actual configured dev name is rejected before inspecting its stopped state. The documented alias checks a different name rather than the configured target.

Executed exact-method proof through the actual compiled ColdGate and a synthetic stopped-state Probe (no Docker process):

```
realDev=refused cause=IllegalArgumentException inspections=0
unmappedDevAlias=accepted inspections=1
```

Fix: replace the unmapped dev alias with `pixelclub-arclight-dev` in allowlist/README/tests. Keep `pixelclub-fabric` only as an explicitly established planned target. Require a fixture assertion that the configured dev name reaches the probe and Running/Restarting/Paused or changed StartedAt still refuses it. This finding verifies local configuration/source consistency, not live Docker inventory.

### [MEDIUM] Public damage-policy wrapper bypasses UNKNOWN for self while the snapshot is unhealthy

Location: main `src/main/java/com/pixelclub/teams/TeamsCore.java:307-308`; cache contract `TeamSnapshots.java:26-27`; API `api/TeamsDamagePolicy.java:10`.

`TeamSnapshots.policy` checks `healthy=false` before its self-UUID exception, correctly returning UNKNOWN until a successful full refresh. `TeamsCore.damagePolicy` instead returns ALLOW for equal UUIDs before delegating. Thus public `TeamsDamagePolicy.evaluate(a,a)` can authorize a secondary-damage consumer during a failed view even though the requested contract says UNKNOWN until full refresh. The current snapshot tests call the cache directly and do not exercise this wrapper seam.

Fix: remove the wrapper's early self ALLOW and delegate to snapshots after the ready/null/legacy gates, or explicitly check snapshot health first. `blockDamage` already retains its separate native self exemption at `TeamsCore.java:311`, so the API fix need not change that behavior. Add wrapper-level regression coverage for failed -> partial refresh -> full refresh with both distinct and equal UUIDs. Evidence rung: source control-flow; no native damage claim.

## Paths verified; no additional defect established

- **File/parser binding:** ColdFiles.read requires UTF-8, format=1 and a rows array; strict recursive parsing rejects duplicate keys, trailing content and excessive depth (`ColdFiles.java:34-55`, `:118-143`). Integer IDs exclude nonpositive/reserved/duplicate teams. Native envelopes require the exact four keys, size/slot consistency, canonical checksum and record shape (`:80-99`). Import binds the artifact to exact snapshot bytes, original per-row UTF-8 hashes, matching converted IDs and the full current nonempty donor rowset (`ColdChests.java:125-138`, `:223-227`). Semantic registry/SNBT/component equivalence is explicitly outside this standalone shape validator and still needs converter evidence.

- **Atomic import/replay:** `ColdChests.java:108-119` rechecks settled receipts, donor bytes and Docker gate before JDBC commit. `TeamStore.importChests:637-659` locks teams/source/native rows, verifies every seed/replay before the first insert, accepts only identical generation-1 replay and inserts the batch in one transaction. Donor blobs remain unchanged. Injected later insert failure leaves no earlier addon row in the H2 fixture.

- **Rollback/source binding:** `ColdChests.java:174-219` binds reverse artifact to exact native export, validates reverse hash/serialization header, equal complete native rowsets and current raw JSON/generation/original hashes, then locks the source/native rows. New native rows require absent/empty legacy donor; converted rows require intact original source hashes. Donor restoration and addon deletion share one JDBC transaction and completed journals are retained. Injected later restore failure rolls back earlier donor update/addon deletion in H2. A successful rollback rejects replay of the old nonempty snapshot.

- **Preview/read-only boundary:** export-native uses reads and explicitly rolls back its read transaction (`ColdChests.java:155-171`); it writes only its new export artifact. Import/rollback refuse before their body when --apply is absent (`:85-90`). There is no separate import dry-run command. DDL provisioning is confined to explicitly applied import (`:139-145`); it is distinct from the atomic chest-row batch.

- **Pending refusal:** requireSettled rejects nonterminal money/chest rows, including ordinary CHEST/ESCROW, and skips only invitation/identity metadata (`ColdChests.java:247-251`). All three modes are exercised with both pending bank and escrow fixtures. This verifies the SQL-journal boundary; the standalone tool does not independently read the application's filesystem WAL.

- **Output failure/commit uncertainty:** CREATE_NEW output is reserved before SQL mutation, but proof write/fsync and final Docker check can still fail after SQL commit (`ColdChests.java:144-150`, `:194-219`). README's uncertainty paragraph correctly requires coordinator state inspection for any nonzero --apply result, including an empty reserved proof. Therefore COLD_CHESTS_REFUSED must not be interpreted as definitely rolled back. No additional defect is claimed against this documented contract. Actual commit-I/O ambiguity, nontransactional MySQL table engines and live filesystem fsync were not fault-tested.

- **Stopped/StartedAt gate:** typed false Running/Restarting/Paused and parseable StartedAt are mandatory; later gate checks compare StartedAt to the initial observation (`ColdGate.java:45-63`). Fixtures reject malformed/unavailable/still-running/restarted states. This detects sampled state changes; it does not atomically exclude a real concurrent container start during commit.

- **Cache/auth separation:** TeamSnapshots publishes a volatile immutable View; synchronized refresh builds a replacement and any SQL/runtime failure marks health false (`TeamSnapshots.java:12-22`). Partial team/allies/name refresh preserves old health; only full refresh sets true. Cache subject/member lists and Team/Member fields are defensively copied. `TeamsCore.requireTeam/requireMember:99-103` still read SQL for mutation authorization; bank/store mutation paths do not authorize from snapshots. Public TeamsDamagePolicy exposes only evaluate. Cache lookups execute no SQL in the synthetic connection-count test.

## Executed acceptance and limits

Fresh javac Java21 compilation of worktree store/tools/test sources plus main TeamSnapshots/test sources; existing enum API class only on classpath. Ran the unedited JUnit assertion bodies with fresh setup/TempDir through reflection: **12/12 ColdChestsTest + 2/2 TeamSnapshotsTest = 14 passed, 0 failed**. n=14 assertion cases; window=one local completed run; interval=one completed case. Extra name-gate proof n=2 names; window=one local run; interval=one probe attempt.

Evidence is **executed/state in synthetic H2**, with native Minecraft/live SQL/Docker durability unverified. Verdict: **fix the two seams before merging; other reviewed fixture paths pass.**
