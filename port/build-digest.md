# Exact Fabric build and overlay contract

Build pins: Gradle 8.14 wrapper copied unchanged from `legendaryreroll-fabric`; Loom 1.9.2; official Mojang mappings; MC 1.21.1; Java 21; Loader 0.16.0; Fabric API 0.116.0+1.21.1. SGUI 1.6.1+1.21.1 and fabric-permissions-api 0.3.1 are the only new jar-in-jar dependencies. JUnit 5.11.4 + H2 2.1.214 test dependencies run with `build/isolated-test-runtime` as working directory.

## Local inputs

- `libs/JustTeams-Fabric-2.5.5.jar`: SHA-256 `162dfd8c551497d08fcfe64dbbcc09437812a8ca1ae3eddf442408e6cf72e86a`. Supplied from licensed scratch artifact, ignored by git. Both Gradle and overlay reject another hash. Plain compileOnly plus testImplementation: includes original ABI, relocated Hikari, Adventure and SnakeYAML already in the baseline; no duplicate runtime libraries.
- `libs/pxc-placeholders-0.1.4.jar`: supplied from `scratch/papi-prep-20261002/ready`; `modCompileOnly`, never bundled. Actual id `pxc-placeholders`, declared optional in metadata.
- Java: `C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`.

## Commands and outputs

PowerShell: set `$env:JAVA_HOME` to the Java path above; `./gradlew.bat --offline compileJava test build --console=plain`. First dependency resolution may need online mode. Override local paths with `-PbaselineJar=...` and `-PpythonExecutable=...`.

`remapJar` creates `build/libs/JustTeams-Fabric-PXC-2.5.5+pxc.1.jar` (owned patch only). `overlayJar`, also part of `build`, creates release `build/libs/JustTeams-2.5.5+pxc.1.jar` and `build/reports/overlay.json`. Never deploy the owned patch alone.

Independent verification: `F:/Pixelclub/tools/.venv/Scripts/python.exe tools/overlay.py verify --base libs/JustTeams-Fabric-2.5.5.jar --patch build/libs/JustTeams-Fabric-PXC-2.5.5+pxc.1.jar --output build/libs/JustTeams-2.5.5+pxc.1.jar`.

Overlay retains every baseline payload including all original classes, embedded libraries and license resources; only Fabric metadata is rewritten and root signature files are removed when present. All new classes must be under `com/pixelclub/teams/`. Conflicting baseline resource/class payloads are rejected. Native entrypoint is solely `com.pixelclub.teams.TeamsMod`; id/version remain `justteams` / `2.5.5+pxc.1`. Baseline contact/license metadata is retained. Nested descriptors merge with exact SGUI/FPAPI version checks; output entry set, class entry set and every expected payload are verified. ZIP order/timestamps/compression are deterministic.

`remapChestConverterJar` packages `converter/src/main/**` and only main `NativeChestCodec*.class`; no baseline payload, SGUI, FPAPI or Pxc core is included. Loom 1.9.2 setting is `addNestedDependencies=false`. Actual converter source/metadata validation belongs to chest worker and main.

## Bootstrap evidence and limits

Static/build evidence only; no server loaded/executed/state/seen acceptance. `compileJava test` passed with NO-SOURCE before application/test sources existed. A temporary ignored build fixture then compiled actual references to Mojmapped ItemStack/RegistryAccess/ServerPlayer, SGUI, FPAPI, original JustTeamsFabric, Adventure, SnakeYAML and relocated Hikari; its remap changed typed Minecraft references to intermediary names. The converter remap task also passed before converter source existed.

Fixture overlay preserved all 4,844 baseline class payloads and 5,204 non-metadata entries, adding one fixture class (n=1 fixture overlay, window=2026-10-02 bootstrap, interval=single run). Seven fixture checks passed: deterministic repeated output (n=2 outputs, immediate repeat), class-set equality/remapped references, and rejection of changed original payload, wrong baseline hash, original resource overwrite, foreign class namespace and old initializer metadata. Fixture jars under `build/bootstrap` and `build/libs/justteams-build-fixture-*` are test-only and must never be booted or deployed.

Real application compile, H2 tests, runtime acceptance, GUI/client observation and deployment remain main's responsibility. No dev/PRD mutation or boot was performed.
