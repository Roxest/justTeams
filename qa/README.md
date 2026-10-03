# Isolated native JustTeams runtime fixture

This addon and harness are QA only. They require the explicit fixture marker,
strict `server-ip=127.0.0.1`, console source, and known synthetic identities.
Carpet mode requires `-Dpxcteams.testActors=true`. The separately marked real-auth
fixture omits that flag and requires the actual native AuthState session before
the helper can operate on a real ServerPlayer. They must never be installed on shared dev or
PRD. No real account files, licences, players or worlds are copied. The later
private GUI stage contains only the six files explicitly authorised by root,
with local H2/Redis isolation and source/output hash proof.

The pinned runtime evidence and current limitations are in `port/runtime-digest.md`.
The harness has fixed local paths for the authorised one-off fixture. It is not a
generic server management utility.

Preparation copies only source rig mods/libraries/launcher, excludes existing
JustTeams/converter mods, adds the candidate and cached Carpet, replaces optional
pxcpapi with0.1.4, extracts only public bundled YAML, and applies safe H2/public
fee/cap/home/server-id overrides. `tools/FixtureBootstrap.java` must initialize
the marked fresh local H2 file before boot. It refuses an existing fixture DB.

Build the QA addon separately:

```powershell
& 'F:/Pixelclub/justteams-fabric-pxc/gradlew.bat' -p qa build --offline --no-daemon
```

Launch the own rig with `qa/rig.py boot`, query bounded health using `summary`,
and queue own console commands using `send`. The runner records per-boot candidate
SHA/mod count, hides the Java window, caps Java at4G and forwards only its own
mailbox. Stop with `send stop`; wait for its own runner exit before any jar swap.
Never overwrite a live jar and never terminate an unrelated process.

`prepare_auth.py` builds only a separate synthetic local H2 auth backend; its
random account credential is owner-only outside the rig and git. Never print it
or read the existing eyes profile secret. `vanilla_auth_client.py` uses actual
loopback login and native password commands. PxcAC requires the approved client
for sustained sessions; the helper never spoofs its handshake or changes auth.
`verify_auth_boot.py` records seven native weave markers, provider/health and the
authorised six-file private GUI stage hashes. `cold_chest.py` compares exact H2
JSON/generation/source against a retained proof while the JVM is cold. A failed
AC-interrupted custody attempt remains explicitly failed even if its durable
state can be inspected independently. Root owns the approved eyes client.

`run_cases.py bank` is for a new seed; `bank-resume` repeats bank behaviour without
resetting wallets; `resume` spawns exact cached synthetic identities after a cold
restart. `gui`, `chest`, and `home-cap` execute respective sequential assertions.
Evidence is saved in the rig's `qa-evidence` and `qa-report.json`. Every attempt is
retained, including corrected harness assumptions.

Vanilla nested command dispatch queues until the QA handler returns. The Python
harness follows it with a separate server-thread snapshot barrier. Menu tests
invoke the actual native menu handler with PICKUP/QUICK_MOVE/SWAP; they are server
state evidence, not client packet/render/seen evidence.

`wallet_fault.py provider-only` tests the real provider's explicit/implicit save
failures without creating a bank intent. `full` requires an unfrozen clean seed
and also tests bank quarantine and stable-intent refusal. Both obstruct only the
known synthetic owner receipt, keep the temporary backup outside the rig and
restore the exact file in `finally`. Never use this script with a real account.

`wallet_lock_fault.py` keeps the actual synthetic receipt readable/present while a
Windows sharing handle blocks write/delete. This reaches the bank's unknown-leg
quarantine instead of making hasAccount false before the first effect. Follow it
with a cold restart, `run_cases.py resume`, and `wallet_restart_verify.py`.
`reset_seed.py` requires the old own Java PID absent and archives only synthetic
H2/WAL outside the rig before the exact bootstrap creates a new seed.

Focused candidate97 checks use `focus97.py normal`, cold restart,
`home-readback`, `sql-fault`, cold restart, then `login-readback`.
`normal` records original save-body reservation observations and paid home state.
The SQL fault probe closes the actual pool after the next real CHEST PREPARED
commit; it never changes JDBC results or replaces the provider. Snapshot captures
local actor/inventory/cursor/lease/quarantine state even when SQL is unavailable.
The test mixin package is `qa.mixin`; ordinary entrypoint/helper classes stay
outside the declared mixin package.

Carpet intentionally ignores literal disconnect reasons. `protocol_quarantine.py`
uses actual vanilla protocol767 login/configuration packets on the fixed own
loopback port with the known synthetic Owner UUID; it requires the native
quarantine reason and server EOF. It neither authenticates a user nor sends
gameplay/financial commands. Packet ids were verified against the locally cached
official1.21.1 ConfigurationProtocols/LoginProtocols bytecode. This is transport
state proof; it does not claim rendered GUI or a real pxc-auth session.

Fixtures may demonstrate loaded/executed/state rungs. Real pxc-auth session
context, rendered GUI, donor full176/79 import, and production restart/cutover are
separate acceptance requirements.

`fresh_pinned.py` stages the immutable b613 baseline with fresh synthetic seed and
public resources. `matrix_b613.py` covers cache/PAPI/aliases/roles/admin/fee gates,
real randomized40 transfers/cold readback and survival ESCROW. It records failed
harness assumptions as well as genuine failures. `final_latest.py` covers the
authorised final creative-return, scientific-codec, recipient claim and invalid
preflight regressions. Check the digest's exact SHA before staging any artifact.
The creative-loss b613 seed is retained outside the rig; no restoration is hidden.
