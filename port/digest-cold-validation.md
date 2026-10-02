# Cold shape/checksum artifact validation

Read-only standalone Java21, actual current ColdFiles.inventory and compiled NativeChestCodec
semanticSha256/parseStrict. Gson2.11.0 and Minecraft Gson2.10.1 produced identical results.
No registry/MC decode, DB, Docker, server, private config, original artifact write or raw item output.
Evidence rung: executed/state; window=one complete invocation per Gson version; interval=one row.

Artifacts under `F:/Pixelclub/chest-codec-rig-arclight-20261002-proof/chest-conversion/`:

| Input | Passed/rows | SHA256 of exact input bytes |
|---|---:|---|
| native-output.json | 79/79 | e883138538d2d2ba0cb9bd47d91da21e2c4eb9691a2698a15b50812642de9e74 |
| native-input.json, reverse80 source | 80/80 | 3f6feaf165a00b61cc648684f1efebda6557133bb6fdf4e52bcaf78019c7d434 |
| fixtures.json, rich synthetic fixture | 1/1 | 8f96d5ab290b235d51202c411fe3b6f27fa91421d28136a05e912c4d5a4a5103 |

Zero artifact row failures. This establishes current CLI shape/checksum compatibility with the
audited converted rows and rich fixture; it adds no registry or GUI seen claim.

## Exact primitive Float mismatch

Typed JsonPrimitive(Float) -> Native semantic hash -> Native parseStrict -> semantic hash:

- `1.0E-5` becomes `0.000010` after parsing.
  Before: `ac7fd3bb54aaf82b1d9f3b3ba0a681e9a81c8f2c0a0058b275b2c79f14ba82dd`
  After: `fb2949c948986bf3aa61465f8c5430132f7baecef1712a60b086fb23decbe5aa`
- `1.0E20` becomes `1.0E+20` after parsing.
  Before: `158609d8c65aa7ded0f4af0593f7194ba757aac31b1258ea421add63d7a2db91`
  After: `8a855041eac675023d57faf24bf208d3821162c97ea9edc71519023322c26480`

Cold parser/hash equals Native after-hash in both cases. The instability is encode-side typed Float
serialization versus parsed BigDecimal spelling, not a disagreement between the two parsers.
Such future encoder values can fail their own checksum roundtrip despite equal numeric values.
Do not characterize the current 79/80/1 artifacts as invalid: all passed. Main owns any canonical
number fix and backward-compatibility/registry regression validation; no application source changed here.

Helper: `qa/cold-validation/ColdValidation.java`. Compile/run with store/main compiled classes,
Gson, DFU8.0.16, Brigadier1.3.10 and cached Mojmaps MC1.21.1 on the host classpath. Its only
methods on NativeChestCodec are parseStrict/semanticSha256; no server entrypoint is invoked.
