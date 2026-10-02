# Cold shape/checksum artifact validation - fixed main 05e1b0c

Read-only Java21; BOTH NativeChestCodec and ColdFiles compiled classes came exclusively from
MAIN build/classes/java/main, not the worker's older classes. They were copied to an ignored
worker build directory to avoid MAIN clean/recompile races. No registry/MC decode, DB, Docker,
server, private config, app source edit or original artifact write. Evidence: executed/state;
window=one complete invocation per Gson version; interval=one row.

Artifacts under `F:/Pixelclub/chest-codec-rig-arclight-20261002-proof/chest-conversion/`:

| Input | Passed/rows | SHA256 of exact input bytes |
|---|---:|---|
| native-output.json | 79/79 | e883138538d2d2ba0cb9bd47d91da21e2c4eb9691a2698a15b50812642de9e74 |
| native-input.json, reverse80 source | 80/80 | 3f6feaf165a00b61cc648684f1efebda6557133bb6fdf4e52bcaf78019c7d434 |
| fixtures.json, rich synthetic fixture | 1/1 | 8f96d5ab290b235d51202c411fe3b6f27fa91421d28136a05e912c4d5a4a5103 |

Zero failures; unchanged artifact bytes/checksums remain accepted. No new registry/GUI seen claim.

## Fixed number roundtrip

Native typed -> semanticSha256 -> parseStrict -> semanticSha256 equals Cold parsed/hash:

- Float `1.0E-5` parses to `0.000010`; all required hashes:
  `fb2949c948986bf3aa61465f8c5430132f7baecef1712a60b086fb23decbe5aa`.
- Float `1.0E20` parses to `1.0E+20`; all required hashes:
  `8a855041eac675023d57faf24bf208d3821162c97ea9edc71519023322c26480`.
- Double `1.0E-30` retains that spelling; all required hashes:
  `50bd35e4af2885656e91d46e4686b2c50112923d5cea64a33d389f4e51c3daab`.

Earlier Native mismatch from6084c65 is fixed in05e1b0c. An extra diagnostic remains: Cold
semanticHash invoked directly with TYPED Float gives old
`ac7fd3bb54aaf82b1d9f3b3ba0a681e9a81c8f2c0a0058b275b2c79f14ba82dd` for1e-5 and
`158609d8c65aa7ded0f4af0593f7194ba757aac31b1258ea421add63d7a2db91` for1e20. This is not
the CLI inventory path: Cold.inventory hashes parsed BigDecimal values, which match fixed Native.
Helper reports cold_typed_equal separately without conflating it with production acceptance.

Helper `qa/cold-validation/ColdValidation.java` fails for artifact rows or required
Native-before/Native-after/Cold-parsed mismatch. Host classpath: MAIN classes, Gson2.10.1/2.11.0,
DFU8.0.16, Brigadier1.3.10 and Mojmaps MC1.21.1. Native methods used: parseStrict/semanticSha256
only. Main's separate ItemStack encode/decode/reencode test is outside this helper.

Captured class SHA256: ColdFiles
`abf0a24689630bc79e5b92cbf2cd105b916acbcab92e96048c7f2ae26458f0cc`;
NativeChestCodec `0b027c71b0126b1fcc4d2f4675cf34e565d8d2bc99cbf185fd051b0f666e8e16`.
