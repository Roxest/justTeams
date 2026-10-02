"""Create and operate a unique loopback-only chest codec rig, never a shared server.

Copies jar/loader libraries only. Never copies config, licenses, worlds, player data,
plugins, server.properties, credentials or an existing rig process.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def free_port(port):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", port))


def create(args):
    root = args.root.resolve()
    source = args.source.resolve()
    if root.exists() or root.parent != Path("F:/Pixelclub").resolve() or not root.name.startswith("chest-codec-rig-"):
        raise SystemExit("Requires a new F:/Pixelclub/chest-codec-rig-* directory")
    free_port(args.port)
    if shutil.disk_usage(root.parent).free < 8 * 1024**3:
        raise SystemExit("Requires at least 8GB disk free")
    mods = sorted((source / "mods").glob("*.jar"))
    if len(mods) < 100:
        raise SystemExit("Full item registry candidate required; refusing a minimal rig mod set")
    root.mkdir()
    for directory in ("libraries", "versions"):
        shutil.copytree(source / directory, root / directory)
    launcher = source / args.launcher
    if not launcher.is_file():
        raise SystemExit("Missing exact source loader launcher")
    shutil.copy2(launcher, root / launcher.name)
    (root / "mods").mkdir()
    manifest = []
    for jar in mods:
        shutil.copy2(jar, root / "mods" / jar.name)
        with zipfile.ZipFile(jar) as archive:
            metadata = json.loads(archive.read("fabric.mod.json"), strict=False) if "fabric.mod.json" in archive.namelist() else {}
        manifest.append({"file": jar.name, "sha256": sha(jar), "id": metadata.get("id"), "version": metadata.get("version")})
    shutil.copy2(args.converter, root / "mods" / args.converter.name)
    (root / "chest-conversion").mkdir()
    shutil.copy2(args.input, root / "chest-conversion" / "donor-input.json")
    (root / ".justteams-chest-isolated").write_text("isolated-file-only-codec-proof\n", encoding="ascii")
    (root / "source-mod-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    (root / "eula.txt").write_text("eula=true\n", encoding="ascii")
    (root / "server.properties").write_text(f'''server-ip=127.0.0.1
server-port={args.port}
enable-rcon=false
online-mode=false
enforce-secure-profile=false
level-name=codec-proof-world
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-players=1
motd=Isolated chest codec proof
gamemode=creative
difficulty=peaceful
enable-query=false
enable-jmx-monitoring=false
sync-chunk-writes=true
allow-flight=true
''', encoding="ascii")
    print(json.dumps({"rig": str(root), "mods": len(mods), "port": args.port,
                      "licenses_copied": 0, "configs_copied": 0, "players_copied": 0}))


def launch(args):
    root = args.root.resolve()
    if root.parent != Path("F:/Pixelclub").resolve() or not root.name.startswith("chest-codec-rig-") \
            or not (root / ".justteams-chest-isolated").is_file():
        raise SystemExit("Not an owned isolated chest rig")
    properties = dict(line.split("=", 1) for line in (root / "server.properties").read_text().splitlines() if "=" in line)
    if properties.get("server-ip") != "127.0.0.1" or properties.get("enable-rcon") != "false":
        raise SystemExit("Rig must be loopback with RCON disabled")
    free_port(int(properties["server-port"]))
    if (root / "runner.pid").exists():
        raise SystemExit("Runner record exists; refusing a second launch")
    # Do not expose host credentials to copied mods. No SSH/DB tokens are inherited.
    environment = {key: value for key, value in os.environ.items() if key.upper() in {
        "PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "APPDATA", "LOCALAPPDATA", "USERPROFILE"
    }}
    log = root / f"codec-console-{time.time_ns()}.log"
    (root / "last-log.txt").write_text(log.name, encoding="ascii")
    commands = root / "codec-commands.txt"
    planned = args.command or ["jtchestconvert forward"]
    commands.write_text("\n".join(planned) + "\n", encoding="ascii")
    with log.open("wb") as stream:
        process = subprocess.Popen([str(args.java), "-Xms512M", "-Xmx4G",
                                    "-Dpixelclub.chestConverter.isolated=true",
                                    "-Dfabric.debug.discoveryTimeout=600", "-jar", args.launcher, "nogui"],
                                   cwd=root, stdin=subprocess.PIPE, stdout=stream, stderr=subprocess.STDOUT,
                                   env=environment, creationflags=subprocess.CREATE_NO_WINDOW)
        (root / "runner.pid").write_text(str(process.pid), encoding="ascii")
        try:
            deadline = time.monotonic() + args.timeout
            dispatched = False
            command_index = 0
            command_start = 0
            completed = []
            conversion_pass = False
            while process.poll() is None and time.monotonic() < deadline:
                text = log.read_text(encoding="utf-8", errors="replace")
                if not dispatched and ")! For help, type" in text:
                    process.stdin.write((planned[command_index] + "\n").encode("ascii"))
                    process.stdin.flush()
                    dispatched = True
                    command_start = len(text)
                response = text[command_start:]
                if dispatched and ("Chest conversion PASS:" in response or "Chest conversion ABORT;" in response
                                   or "Native chest codec PASS:" in response or "Native chest codec ABORT;" in response
                                   or "Native chest fixtures PASS:" in response or "Native chest fixtures ABORT;" in response):
                    conversion_pass = "ABORT;" not in response
                    completed.append({"command": planned[command_index], "pass": conversion_pass})
                    if "ABORT;" in response or command_index + 1 == len(planned):
                        break
                    command_index += 1
                    process.stdin.write((planned[command_index] + "\n").encode("ascii"))
                    process.stdin.flush()
                    command_start = len(text)
                time.sleep(1)
        finally:
            if process.poll() is None:
                process.stdin.write(b"stop\n")
                process.stdin.flush()
                try:
                    process.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    # This PID was created here; never search for or stop another rig/server.
                    process.kill()
                    process.wait(timeout=30)
            (root / "runner.pid").unlink(missing_ok=True)
    print(json.dumps({"rig": str(root), "exit_code": process.returncode, "commands": completed,
                      "conversion_pass": conversion_pass and len(completed) == len(planned),
                      "output_files": sorted(path.name for path in (root / "chest-conversion").glob("*output.json"))
                                      + sorted(path.name for path in (root / "chest-conversion").glob("*proof.json")),
                      "log": str(log)}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    setup = sub.add_parser("create")
    setup.add_argument("--source", type=Path, required=True)
    setup.add_argument("--root", type=Path, required=True)
    setup.add_argument("--converter", type=Path, required=True)
    setup.add_argument("--input", type=Path, required=True)
    setup.add_argument("--port", type=int, default=25651)
    setup.add_argument("--launcher", default="arclight-fabric-1.21.1-1.0.2-SNAPSHOT-0769551.jar")
    boot = sub.add_parser("launch")
    boot.add_argument("--root", type=Path, required=True)
    boot.add_argument("--java", type=Path, default=Path("C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin/java.exe"))
    boot.add_argument("--launcher", default="arclight-fabric-1.21.1-1.0.2-SNAPSHOT-0769551.jar")
    boot.add_argument("--command", action="append", choices=["jtchestconvert forward", "jtchestconvert reverse", "jtchestconvert verify", "jtchestconvert fixtures"])
    boot.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    create(args) if args.mode == "create" else launch(args)


if __name__ == "__main__":
    main()
