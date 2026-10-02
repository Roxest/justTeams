"""Export only donor chest rows over SELECT-only SSH. Never writes a remote file or database.

The default records a live consistent SQL snapshot for rig proof; --require-cold refuses a
running PRD container. A live snapshot is never labeled a cold cutover snapshot.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import textwrap
from pathlib import Path


def module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def running(client) -> bool:
    _, out, err = client.exec_command(
        "docker inspect --format '{{.State.Running}}' pixelclub-arclight", timeout=30
    )
    value = out.read().decode("ascii").strip()
    if out.channel.recv_exit_status() != 0 or value not in {"true", "false"}:
        raise RuntimeError("Cannot verify PRD container running state")
    return value == "true"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--pxc", type=Path, default=Path("F:/Pixelclub/tools/pxc.py"))
    parser.add_argument("--db-helper", type=Path,
                        default=Path("F:/Pixelclub/tools/eco/player-audit-0929/dbconn.py"))
    parser.add_argument("--require-cold", action="store_true")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or "scratch" not in {part.lower() for part in output.parts}:
        raise SystemExit("Output must be new and under a local scratch directory")
    helper = args.db_helper.read_text(encoding="utf-8")
    if "def connect():" not in helper or "TRANSACTION READ ONLY" not in helper:
        raise SystemExit("Requires the known SELECT-only DB helper")
    client = module(args.pxc, "chest_pxc").connect()
    try:
        was_running = running(client)
        if args.require_cold and was_running:
            raise RuntimeError("Cold snapshot refused: PRD container is running")
        export_body = '''
import hashlib, json
conn, cur = connect()
try:
    cur.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
    cur.execute("SELECT UTC_TIMESTAMP(6) AS snapshot_utc")
    timestamp = str(cur.fetchone()["snapshot_utc"])
    cur.execute("SELECT team_id, inventory_data FROM pixelclub.donut_team_enderchest ORDER BY team_id")
    rows = []
    for row in cur.fetchall():
        blob = row["inventory_data"]
        if not isinstance(blob, str) or not blob:
            raise ValueError("Missing donor chest blob")
        rows.append({"team_id": int(row["team_id"]), "blob": blob,
                     "source_sha256": hashlib.sha256(blob.encode("utf-8")).hexdigest()})
    print(json.dumps({"format": 1, "snapshot_utc": timestamp, "rows": rows}, ensure_ascii=False))
finally:
    conn.rollback()
    cur.close()
    conn.close()
'''
        script = helper + "\nimport sys\ntry:\n" + textwrap.indent(export_body, "    ") + '''
except Exception as failure:
    print(json.dumps({"error_type": type(failure).__name__, "error_code": getattr(failure, "errno", None)}))
    sys.exit(1)
'''
        stdin, stdout, stderr = client.exec_command("python3 -", timeout=60)
        stdin.write(script)
        stdin.flush()
        stdin.channel.shutdown_write()
        data = stdout.read()
        error = stderr.read()
        if stdout.channel.recv_exit_status() != 0:
            # Remote exception text may contain credentials; never relay it.
            try:
                failure = json.loads(data)
                code = int(failure["error_code"]) if failure.get("error_code") is not None else None
                kind = failure.get("error_type", "UnknownError")
                if not isinstance(kind, str) or not kind.isidentifier():
                    kind = "UnknownError"
                raise RuntimeError(f"SELECT-only export failed: {kind}, code={code}; message withheld")
            except (ValueError, KeyError):
                raise RuntimeError("SELECT-only export failed; remote error text withheld") from None
        document = json.loads(data)
        if running(client) != was_running:
            raise RuntimeError("PRD running state changed during snapshot")
        document["cold_verified"] = not was_running
        document["source"] = "SELECT team_id, inventory_data FROM pixelclub.donut_team_enderchest"
        output.parent.mkdir(parents=True, exist_ok=True)
        data = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        with output.open("xb") as stream:
            stream.write(data)
        print(json.dumps({"rows": len(document["rows"]), "cold_verified": document["cold_verified"],
                          "sha256": hashlib.sha256(data).hexdigest(), "output": str(output)}))
    finally:
        client.close()


if __name__ == "__main__":
    main()
