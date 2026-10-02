# Isolated JustTeams chest conversion

`./gradlew remapChestConverterJar` builds the standalone converter mod; it packages only
the converter and `NativeChestCodec`. It never registers the production Teams entrypoint,
connects to SQL, updates a donor row, or writes outside `chest-conversion/`.

The runtime requires all three: JVM `-Dpixelclub.chestConverter.isolated=true`, server
binding `127.0.0.1`/`::1`, and a `.justteams-chest-isolated` file in the rig root. Commands
are console-only and require level 4. Existing output is refused. Any row/slot/class/item/
component/size/semantic error aborts the entire batch before an atomic output is installed.
Native format 1 requires its envelope checksum; all converter-produced inventories include it.

## Inputs and outputs

`chest-conversion/donor-input.json`:

```json
{"format":1,"rows":[{"team_id":1,"blob":"<original Base64 lines>","source_sha256":"<SHA256 of exact blob UTF8>"}]}
```

`jtchestconvert forward` uses the live CraftServer classloader's BukkitObjectInputStream,
validates the declared slot count, converts every non-null slot with CraftItemStack.asNMSCopy,
requires Bukkit metadata equality, and encodes registry-aware ItemStack.CODEC JSON. The
Minecraft CustomData components use the same codec's SNBT alternative, derived from its
typed NbtOps tree, to retain byte/short/int/long widths and typed arrays, including nested items.
Every nonempty native slot is `{item:<ItemStack.CODEC JSON>,nbt:<typed ItemStack.CODEC SNBT>,components_sha256:<hash>}`.
Both forms must decode with the same registry, without any codec error. The typed form preserves
arbitrary mod NBT components such as `sophisticatedcore:render_info_tag`. Re-encoding the result
must reproduce the complete source JSON record, and exact typed state is compared separately.
No representation is used when the other fails; differing transient components also abort.
The component hash covers every effective persistent component, including item defaults, so a
target registry with changed default item properties aborts rather than silently changing them.
The output `native-output.json` includes original hash, inventory JSON, every slot's full item
JSON/hash, and a freshly rebuilt reverse donor blob/hash. Original raw exports stay separate.
The Java Wrapper maps are also compared before and after Bukkit deserialize/reserialize,
catching unknown enchantments/meta fields silently discarded before native conversion.
Known CraftMetaItem `internal/custom/unhandled` fields contain Base64 gzip NBT; their typed
decompressed trees are compared, preserving every key, number width, array and opaque PDC.
Other wrapper fields remain strict. This avoids treating gzip byte order as metadata loss.

`chest-conversion/native-input.json`:

```json
{"format":1,"rows":[{"team_id":1,"inventory_json":"<format1 native chest JSON>","original_sha256":null}]}
```

`jtchestconvert reverse` rebuilds the old writeInt+ItemStack/null Java stream with
BukkitObjectOutputStream and the original Base64Coder.encodeLines implementation. It verifies
native→Bukkit→native and the rebuilt blob's decoded per-slot JSON. It supports newly created
native rows/items with a null original hash. It writes `reverse-output.json` only.

`jtchestconvert verify` works without Bukkit on a full-registry pure Fabric rig, validates
every input with the typed native codec, and writes per-slot proof to `fabric-proof.json`.
`jtchestconvert fixtures` creates synthetic named/lore/damaged/enchanted/custom-data sword,
potion, nested shulker, oversized stack and mod-item fixtures in `fixtures.json`.

## Helpers

`tools/chest_export.py --output F:/Pixelclub/scratch/<job>/donor.json` exports allowlisted
`team_id,inventory_data` only via the existing SSH/SELECT-only DB helper. It sends Python over
SSH stdin and writes no remote file. It records a consistent SQL live snapshot with
`cold_verified=false`; `--require-cold` refuses a running PRD container. No command here stops
PRD. Credentials are read inside the known helper and never printed or copied.

`tools/chest_rig.py create` copies jar/loader libraries from a full-registry candidate into
a new uniquely named `F:/Pixelclub/chest-codec-rig-*` directory. No configs, plugins, licenses,
worlds or player files are copied. `launch` checks loopback/free port, launches its own hidden
JVM with a credential-free environment, dispatches specified codec commands, and stops only
the process it created. It records separate console logs. Never point these tools at PRD,
shared dev, or an existing unrelated rig.

`tools/chest_artifacts.py prepare` extracts allowlisted native rows and optionally adds
synthetic post-cutover fixture rows. `compare` compares every original per-slot item/hash
with a pure Fabric/reverse proof, and `inspect` emits aggregate counts/hash/namespaces only.

Runtime proof from a live snapshot does not authorize cutover. Repeat the export after an
approved stop with settled writes, convert all cold rows, verify on the target item registry,
and retain immutable originals plus all manifests and reverse blobs before importing.
