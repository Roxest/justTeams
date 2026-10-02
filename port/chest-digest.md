# Chest codec and isolated conversion

ABI: `NativeChestCodec.encode(List<ItemStack>, RegistryAccess): String`,
`decode(String, RegistryAccess): List<ItemStack>`. Official Mojmaps MC 1.21.1.
Format 1: envelope `format,size,slots,sha256`; checksum required, size 9..54, multiple of 9.
Slot is null or `{item,nbt,components_sha256}`. `item` is registry-aware
ItemStack.CODEC JSON, `nbt` the same codec's typed SNBT, and the hash covers
every effective persistent component including item defaults. Both codecs must
decode; final encode must reproduce the full source JSON semantically (Gson).
Unknown keys/items/components, any DataResult error/partial result, invalid size,
hash mismatch and state loss abort. No empty-item fallback.

Why dual representation: plain JsonOps narrows NBT numeric types/typed arrays,
including real `sophisticatedcore:render_info_tag`. Known Minecraft CustomData
components also use the codec's SNBT alternative. Registry-aware typed NBT retains
arbitrary registered mod NBT components. Floats are compared after serializing and
reparsing JSON, avoiding Float.doubleValue vs JSON-decimal false mismatches.
Vanilla redundant default patches (observed empty shulker capacity 27 vs EMPTY)
canonicalize only with exact full effective component state, same item/count and
unchanged transient values. Populated contents and arbitrary PDC stay exact.

`ChestSessionContainer(int teamId,UUID playerId,long generation,List<ItemStack>,BooleanSupplier)`
extends SimpleContainer. Accessors, `revoke/isOpen/snapshot`, and `stillValid` enforce
the player/current lease. Caller owns team-id lock, current membership authorization,
close/kick/reload/disband invalidation and persistence CAS. Initialization bypasses
SimpleContainer#setItem's silent stack clamping; snapshots copy ItemStacks.

Standalone addon: `remapChestConverterJar`. Console-only `jtchestconvert
forward|reverse|verify|fixtures`. Requires explicit isolated JVM flag + loopback
binding + marker; hardcoded local input/output paths only. No SQL/production write.
Forward uses the exact donor Java stream (writeInt + ItemStack/null) and baseline
BukkitObjectInputStream/CraftItemStack; reverse rebuilds every native row including
new post-cutover items with BukkitObjectOutputStream/Base64Coder.encodeLines.
One row failure aborts the entire batch before atomic output. Unknown serialized
classes rejected. Extra raw Wrapper-map audit detects Bukkit fields/enchantments
silently dropped during deserialize; original bytes remain immutable.

Tools: `chest_export.py` SELECT-only allowlisted rows over existing SSH helper,
live consistent snapshot explicitly cold_verified=false; --require-cold refuses
running PRD. `chest_rig.py` copies jars/libraries only to unique loopback rigs,
no config/license/world/player files, cleans host credentials from JVM environment,
stops only its own process. `chest_artifacts.py` prepares/compares per-slot proofs.

Acceptance (state unless labeled otherwise): six isolated JUnit tests PASS; rich
post-cutover fixture contains names/lore/damage/enchant/custom NBT widths and arrays,
potion, nested shulker, count 99, Cobblemon/backpack items. Live donor n=79 rows,
2,376 top-level slots/584 nonempty; forward and reverse passed 2026-10-03 00:36/00:41
ICT. Separate reverse n=80 includes one new fixture; original 79 slot manifests exact.
Pure Fabric n=80 passed 00:46:58 ICT; item registry hash equal, effective default/
component hashes and every per-slot payload exact. Final raw Wrapper audit passed
forward n=79 at 01:25:51 and separate reverse n=80 at 01:25:52 ICT. Known CraftMetaItem
Base64 gzip NBT fields `internal/custom/unhandled` compare typed decompressed SNBT
(primary baseline bytecode confirms NbtIo.writeCompressed), so compression byte order
never masks dropped keys, numeric widths, arrays or opaque PDC. Every other wrapper
field remains strict. Original 79 manifests match both audited reverse and pure Fabric
proofs. Interval=single full batch per proof; windows above on 2026-10-03 ICT.
Both owned JVMs stopped and loopback ports released. No GUI seen claim, no live import,
no cold snapshot or PRD cutover; metadata-bearing evidence stays outside git.

Evidence stays outside git: `F:/Pixelclub/chest-codec-rig-{arclight,fabric}-20261002-proof/chest-conversion/`;
original export `F:/Pixelclub/scratch/justteams-cutover-20261002/chest/donor-live-proof.json`
SHA256 `8ad763914eea3bdc42f3ee5c30ce5abfc066266c9d24e9d87dad12563d135261`.
Audited native SHA256 `e883138538d2d2ba0cb9bd47d91da21e2c4eb9691a2698a15b50812642de9e74`;
audited reverse `9192dab7be3ffa8c455ecbd40654709aa9d401c596309747a37c3022dc3d715d`.
Registry hash `1988ad94eee34da78fbd5228090cae111a1ccf2d7b2e3e2c1dae8c2f0fbca638` is equal.
Final addon SHA256 `5c2dd4761305e93c91a260b3261e7178ff447de40422777b844e3c1d743ee5c3`.
Source/source-registry and all input/output artifacts must be retained for approved
cold conversion and rollback. Partial first rig directory remains because guarded
recursive cleanup was automatically rejected; no other process/workspace touched.
