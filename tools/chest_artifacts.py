"""Prepare file-only native verification/reverse input and compare per-slot proof manifests."""
from __future__ import annotations

import argparse
import hashlib
import json
import base64
import re
from pathlib import Path


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def prepare(args):
    artifact = load(args.artifact)
    rows = [{"team_id": row["team_id"], "inventory_json": row["inventory_json"],
             "original_sha256": row.get("original_sha256")} for row in artifact["rows"]]
    if args.fixtures:
        rows.extend(load(args.fixtures)["rows"])
    if len({row["team_id"] for row in rows}) != len(rows):
        raise SystemExit("Duplicate team ids")
    data = json.dumps({"format": 1, "rows": rows}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    with args.output.open("xb") as stream:
        stream.write(data)
    print(json.dumps({"rows": len(rows), "sha256": hashlib.sha256(data).hexdigest(), "output": str(args.output)}))


def inspect(args):
    artifact = load(args.artifact)
    slots = [slot for row in artifact["rows"] for slot in row["slots"]]
    items = [slot["item"] for slot in slots if slot["item"] is not None]
    namespaces = sorted({item["id"].split(":")[0] for item in items})
    print(json.dumps({"rows": len(artifact["rows"]), "slots": len(slots), "nonempty_slots": len(items),
                      "item_namespaces": namespaces, "file_sha256": hashlib.sha256(args.artifact.read_bytes()).hexdigest(),
                      "item_registry_sha256": artifact.get("item_registry_sha256")}))


def compare(args):
    left, right = load(args.left), load(args.right)
    first = {row["team_id"]: row["slots"] for row in left["rows"]}
    second = {row["team_id"]: row["slots"] for row in right["rows"]}
    if not first.keys() <= second.keys():
        raise SystemExit("Missing proof team rows")
    for team, slots in first.items():
        if slots != second[team]:
            raise SystemExit(f"Per-slot semantic mismatch for team {team}")
    print(json.dumps({"matched_rows": len(first), "additional_rows": len(second) - len(first),
                      "same_item_registry": left.get("item_registry_sha256") == right.get("item_registry_sha256"),
                      "semantic_match": True}))


def classes(args):
    # Descriptor-shaped ASCII strings only, no item names, lore, UUIDs or raw blobs.
    names = set()
    for row in load(args.artifact)["rows"]:
        stream = base64.b64decode(row["blob"])
        for name in re.findall(rb"(?:java|org|com)\.[A-Za-z0-9_.$]{3,160}", stream):
            names.add(name.decode("ascii"))
    print(json.dumps({"descriptor_names": sorted(names)}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("--artifact", type=Path, required=True)
    prep.add_argument("--fixtures", type=Path)
    prep.add_argument("--output", type=Path, required=True)
    stats = sub.add_parser("inspect")
    stats.add_argument("artifact", type=Path)
    descriptors = sub.add_parser("classes")
    descriptors.add_argument("artifact", type=Path)
    comparison = sub.add_parser("compare")
    comparison.add_argument("left", type=Path)
    comparison.add_argument("right", type=Path)
    args = parser.parse_args()
    {"prepare": prepare, "inspect": inspect, "compare": compare, "classes": classes}[args.command](args)


if __name__ == "__main__":
    main()
