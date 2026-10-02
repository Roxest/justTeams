#!/usr/bin/env python3
"""Build and verify a deterministic overlay over the licensed exact Fabric jar.

The baseline is supplied locally, never fetched. Entry payloads from the baseline
remain byte-identical except fabric.mod.json and obsolete signing files.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import tempfile
import zipfile


BASE_SHA256 = "162dfd8c551497d08fcfe64dbbcc09437812a8ca1ae3eddf442408e6cf72e86a"
MOD_ID = "justteams"
VERSION = "2.5.5+pxc.1"
ENTRYPOINT = "com.pixelclub.teams.TeamsMod"
CLASS_PREFIX = "com/pixelclub/teams/"
META = "fabric.mod.json"
SIGNATURE = re.compile(r"^META-INF/[^/]+\.(?:SF|RSA|DSA|EC)$", re.IGNORECASE)
REQUIRED_NESTED_MODS = {"sgui": "1.6.1+1.21.1", "fabric-permissions-api-v0": "0.3.1"}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_jar(path: Path) -> tuple[dict[str, bytes], str]:
    raw = path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(raw)) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)):
            raise ValueError(f"Duplicate entries in {path.name}")
        for name in names:
            parts = name.replace("\\", "/").split("/")
            if name.startswith("/") or "\\" in name or ".." in parts or "\0" in name:
                raise ValueError(f"Unsafe jar entry: {name!r}")
        return {name: jar.read(name) for name in names}, sha256(raw)


def metadata(entries: dict[str, bytes]) -> dict:
    value = json.loads(entries[META])
    if not isinstance(value, dict) or value.get("schemaVersion") != 1:
        raise ValueError("Expected Fabric metadata schemaVersion 1")
    if value.get("id") != MOD_ID:
        raise ValueError(f"Expected mod id {MOD_ID}")
    return value


def nested_descriptors(meta: dict) -> list[dict]:
    jars = meta.get("jars", [])
    if not isinstance(jars, list):
        raise ValueError("Fabric jars metadata must be an array")
    for descriptor in jars:
        if not isinstance(descriptor, dict) or set(descriptor) != {"file"}:
            raise ValueError("Invalid Fabric nested jar descriptor")
        if not isinstance(descriptor["file"], str) or not descriptor["file"].startswith("META-INF/jars/"):
            raise ValueError("Nested jars must be below META-INF/jars/")
    return jars


def expected_entries(base_path: Path, patch_path: Path, version: str) -> tuple[dict[str, bytes], dict]:
    baseline, baseline_sha = read_jar(base_path)
    if baseline_sha != BASE_SHA256:
        raise ValueError(f"Baseline SHA-256 mismatch: {baseline_sha}")
    patch, patch_sha = read_jar(patch_path)
    base_meta, patch_meta = metadata(baseline), metadata(patch)
    if base_meta.get("version") != "2.5.5":
        raise ValueError("Expected exact baseline version 2.5.5")
    if patch_meta.get("version") != version:
        raise ValueError("Patch metadata version does not match requested version")
    if patch_meta.get("entrypoints") != {"main": [ENTRYPOINT]}:
        raise ValueError("Patch must declare the native TeamsMod entrypoint only")
    entrypoint_class = ENTRYPOINT.replace(".", "/") + ".class"
    if entrypoint_class not in patch:
        raise ValueError(f"Missing native entrypoint class: {entrypoint_class}")

    removed = sorted(name for name in baseline if SIGNATURE.fullmatch(name))
    result = {name: data for name, data in baseline.items() if name not in removed}
    added = []
    for name, data in patch.items():
        if name.endswith("/") or name in {META, "META-INF/MANIFEST.MF"}:
            continue
        if SIGNATURE.fullmatch(name):
            raise ValueError(f"Patch signing file is unsupported: {name}")
        if name.endswith(".class") and not name.startswith(CLASS_PREFIX):
            raise ValueError(f"Patch class is outside the owned namespace: {name}")
        if name in result:
            if result[name] != data:
                raise ValueError(f"Patch would change preserved baseline entry: {name}")
        else:
            result[name] = data
            added.append(name)

    # Keep original license/contact and other donor metadata; replace the old initializer.
    merged = dict(base_meta)
    for key, value in patch_meta.items():
        if key not in {"id", "version", "license", "contact", "entrypoints", "jars"}:
            merged[key] = value
    merged.update(id=MOD_ID, version=version, environment="server", entrypoints={"main": [ENTRYPOINT]})
    merged["depends"] = {**base_meta.get("depends", {}), **patch_meta.get("depends", {})}
    merged["depends"].update(fabricloader=">=0.16.0", minecraft="~1.21.1", java=">=21")
    descriptors = {}
    for descriptor in nested_descriptors(base_meta) + nested_descriptors(patch_meta):
        name = descriptor["file"]
        if name not in result:
            raise ValueError(f"Nested jar descriptor has no payload: {name}")
        descriptors[name] = descriptor
    merged["jars"] = [descriptors[name] for name in sorted(descriptors)]
    nested_mods = {}
    for name in descriptors:
        with zipfile.ZipFile(io.BytesIO(result[name])) as nested:
            nested_meta = json.loads(nested.read(META))
        mod_id = nested_meta["id"]
        if mod_id in nested_mods:
            raise ValueError(f"Duplicate nested mod id: {mod_id}")
        nested_mods[mod_id] = {"file": name, "version": nested_meta["version"], "sha256": sha256(result[name])}
    for mod_id, required_version in REQUIRED_NESTED_MODS.items():
        if mod_id not in nested_mods or nested_mods[mod_id]["version"] != required_version:
            raise ValueError(f"Missing exact nested mod: {mod_id} {required_version}")
    for name in result:
        if name.startswith("META-INF/jars/") and name.endswith(".jar") and name not in descriptors:
            raise ValueError(f"Undeclared nested jar: {name}")
    result[META] = (json.dumps(merged, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    report = {
        "baseline_sha256": baseline_sha,
        "patch_sha256": patch_sha,
        "id": MOD_ID,
        "version": version,
        "entrypoints": merged["entrypoints"],
        "baseline_entries": len(baseline),
        "preserved_entries": len(baseline) - len(removed) - 1,
        "baseline_classes": sum(name.endswith(".class") for name in baseline),
        "added_classes": sum(name.endswith(".class") for name in added),
        "added_entries": sorted(added),
        "removed_signature_entries": removed,
        "nested_mods": nested_mods,
    }
    return result, report


def deterministic_write(output: Path, entries: dict[str, bytes]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix=".jar", delete=False) as temporary:
        temporary_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(temporary_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as jar:
            for name in sorted(entries):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 3
                info.external_attr = (0o40755 if name.endswith("/") else 0o100644) << 16
                jar.writestr(info, entries[name], compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        temporary_path.replace(output)
    finally:
        temporary_path.unlink(missing_ok=True)


def verify(output: Path, expected: dict[str, bytes], report: dict) -> dict:
    actual, output_sha = read_jar(output)
    expected_classes = {name for name in expected if name.endswith(".class")}
    actual_classes = {name for name in actual if name.endswith(".class")}
    if expected_classes != actual_classes:
        raise ValueError("Output class entry set differs from baseline plus owned patch classes")
    if set(actual) != set(expected):
        raise ValueError("Output entry set differs from the declared overlay")
    changed = [name for name in expected if actual[name] != expected[name]]
    if changed:
        raise ValueError(f"Output entry payload mismatch: {changed[:5]}")
    final_meta = metadata(actual)
    if final_meta["entrypoints"] != {"main": [ENTRYPOINT]} or final_meta["version"] != report["version"]:
        raise ValueError("Output metadata mismatch")
    return {**report, "output_sha256": output_sha, "output_entries": len(actual),
            "output_classes": len(actual_classes), "verified": True}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["build", "verify"])
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--patch", type=Path, required=True, help="Loom remapJar output, never the named dev jar")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", default=VERSION)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        if args.output.resolve() in {args.base.resolve(), args.patch.resolve()}:
            raise ValueError("Output must not overwrite either input jar")
        expected, report = expected_entries(args.base, args.patch, args.version)
        if args.operation == "build":
            deterministic_write(args.output, expected)
        report = verify(args.output, expected, report)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({key: report[key] for key in
                          ("verified", "version", "preserved_entries", "baseline_classes", "added_classes", "output_sha256")}))
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"overlay: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
