"""justTeams cold-window steps, run from Kraus's PC by the restart coordinator. Never starts or stops a container.

  python tools/jt_window.py import   --target prd|rehearsal     dump -> cold export -> convert -> import (+ verify)
  python tools/jt_window.py swap     --target prd [--dry-run]   plugin jar out, native jar + config/JustTeams in, pxcnotice rule
  python tools/jt_window.py postboot --target prd|rehearsal     read-only boot-log check after the coordinator starts the server
  python tools/jt_window.py rollback --target prd|rehearsal     export-native -> reverse -> rollback (+ verify); prd also unswaps

Exit 0 prints `justteams_window: OK ...`. Exit 9 = HOLD DOWN (keep the server down, keep evidence). Any other exit = HOLD DOWN.
Evidence: F:/Pixelclub/scratch/justteams-cutover-20261002/window-<target>-<UTC>/ (outside git; contains private data).
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
PY = "F:/Pixelclub/tools/.venv/Scripts/python.exe"
SCRATCH = Path("F:/Pixelclub/scratch/justteams-cutover-20261002")
RIG = Path("F:/Pixelclub/chest-codec-rig-prd-20261003")
WORK = "/root/pixelclub/ops/justteams-cutover"
REGISTRY = "1988ad94eee34da78fbd5228090cae111a1ccf2d7b2e3e2c1dae8c2f0fbca638"
CONVERTER = "ca7a0837d812e17b91d512daa544ce5988ad2253a10498b7fd2bdc66ab43cbae"
INCOMING = "a35c9c0c662716bbdbcaebd2e2ecd04110d7138edc0017ae2fc63b54b7bbfe61"
OUTGOING = "36c725c1c15afcb546142b9933a40ee14499cffe55daa1d415f9cca7c4bef8af"
PRD = "/root/pixelclub/pixelclub-cobblemon-server"
TARGETS = {
    "prd": {"container": "pixelclub-arclight", "database": "pixelclub", "props": "/root/pixelclub/ops/secrets/jt-pixelclub.properties"},
    "rehearsal": {"container": "pixelclub-arclight-dev", "database": "jt_rehearsal", "props": "/root/pixelclub/ops/secrets/jt-jt_rehearsal.properties"},
}
NOTICE_ANCHOR = '      "match": "caller:eu.kotori.justTeams.",\n      "label": "TEAM BANK",\n      "icon": "minecraft:iron_ingot"\n    },\n'
NOTICE_RULE = '    {\n      "match": "caller:com.pixelclub.teams.",\n      "label": "TEAM BANK",\n      "icon": "minecraft:iron_ingot"\n    },\n'
YAML = ("config.yml", "messages.yml", "gui.yml", "commands.yml", "placeholders.yml", "quests.yml", "webhooks.yml")


class Hold(Exception):
    pass


def sha(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); return m


HOST = module(HERE / "jt_host.py", "jt_host")


class Window:
    def __init__(self, target: str, step: str):
        self.target, self.cfg = target, TARGETS[target]
        self.dir = SCRATCH / f"window-{target}-{step}-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}"
        self.dir.mkdir(parents=True)
        self.client = HOST.module(HOST.PXC, "jt_window_pxc").connect()
        self.log = []

    def note(self, step, **data):
        row = {"step": step, "utc": time.strftime("%H:%M:%SZ", time.gmtime()), **data}
        self.log.append(row); (self.dir / "window-log.json").write_text(json.dumps(self.log, indent=2), encoding="utf-8")
        print(json.dumps(row)[:400], flush=True)

    def shell(self, command, timeout=600):
        return HOST.shell(self.client, command, timeout)

    def cold(self):
        code, out, _ = self.shell(f"docker inspect -f '{{{{.State.Running}}}} {{{{.State.Restarting}}}} {{{{.State.StartedAt}}}} {{{{.HostConfig.RestartPolicy.Name}}}}' {self.cfg['container']}")
        running, restarting, started, policy = out.split()
        if code != 0 or running != "false" or restarting != "false" or policy != "no":
            raise Hold(f"container not cold with policy no: running={running} restarting={restarting} policy={policy}")
        return started

    def remote_py(self, body, arg=None, timeout=900):
        return HOST.remote_python(self.client, body, arg, timeout)

    def manifest(self, name):
        data = json.loads(self.remote_py(HOST.BODIES["catalog-manifest"], self.cfg["database"]))
        (self.dir / f"{name}.json").write_text(json.dumps(data, indent=2), encoding="utf-8"); return data

    def cli(self, *args):
        quoted = " ".join(args)
        code, out, err = self.shell(f"{HOST.JAVA} -cp {HOST.CLASSPATH} com.pixelclub.teams.tools.ColdChests {quoted} "
                                    f"--properties {self.cfg['props']} --container {self.cfg['container']}", timeout=900)
        self.note("cli", args=args[0], exit=code, stdout=out[:300], stderr=err[-200:])
        if code != 0:
            raise Hold(f"ColdChests {args[0]} exit {code}: {err[-120:]}")
        return json.loads(out)

    def put(self, local: Path, remote: str):
        with self.client.open_sftp() as sftp:
            sftp.put(str(local), remote); sftp.chmod(remote, 0o600)
        code, out, _ = self.shell(f"sha256sum '{remote}'")
        if out.split()[0] != sha(local):
            raise Hold("upload hash mismatch " + remote)

    def get(self, remote: str, local: Path):
        with self.client.open_sftp() as sftp:
            sftp.get(remote, str(local))

    def convert(self, command, source: Path, name_in, name_out) -> Path:
        conv = RIG / "chest-conversion"; aside = conv / f"aside-{time.time_ns()}"; aside.mkdir()
        for f in conv.glob("*.json"):
            shutil.move(str(f), aside / f.name)
        jar = next(RIG.glob("mods/justteams-chest-converter-*.jar"))
        if sha(jar) != CONVERTER:
            raise Hold("rig converter pin mismatch")
        shutil.copy2(source, conv / name_in)
        r = subprocess.run([PY, str(HERE / "chest_rig.py"), "launch", "--root", str(RIG), "--command", command, "--timeout", "900"],
                           capture_output=True, text=True)
        result = json.loads(r.stdout.strip().splitlines()[-1])
        if not result["conversion_pass"]:
            raise Hold(f"{command} failed: {result}")
        out = self.dir / name_out; shutil.copy2(conv / name_out, out)
        doc = json.loads(out.read_text(encoding="utf-8"))
        if doc["source_file_sha256"] != sha(source) or doc["item_registry_sha256"] != REGISTRY:
            raise Hold("conversion not bound to the snapshot or registry changed")
        return out

    def offload(self):
        """Copy every window-* file off the host into the evidence dir, then delete it there (no data left on the server)."""
        code, out, _ = self.shell(f"ls {WORK} | grep '^window-' || true")
        for name in [n for n in out.split() if n.startswith("window-")]:
            self.get(f"{WORK}/{name}", self.dir / name); self.shell(f"rm -f -- '{WORK}/{name}'")
        self.note("offloaded", files=len(out.split()))

    def close(self):
        self.client.close()


def step_import(w: Window):
    started = w.cold(); w.note("cold", startedAt=started)
    before = w.manifest("manifest-before")
    if any(k.startswith("pxc_teams_chests") and before[k]["rows"] for k in before if isinstance(before[k], dict)):
        raise Hold("native chest rows already present")
    dump_remote = f"{WORK}/window-dump-{w.cfg['database']}-{time.time_ns()}.sql"
    w.note("dump", result=w.remote_py(HOST.BODIES["dump"], json.dumps([w.cfg["database"], dump_remote])))
    w.get(dump_remote, w.dir / "dump.sql"); w.shell(f"rm -f -- '{dump_remote}'")
    w.note("dump-offserver", sha256=sha(w.dir / "dump.sql"), bytes=(w.dir / "dump.sql").stat().st_size, removedFromHost=True)
    export = subprocess.run([PY, str(HERE / "chest_export.py"), "--output", str(w.dir / "donor-cold.json"), "--require-cold",
                             "--container", w.cfg["container"], "--database", w.cfg["database"]], capture_output=True, text=True)
    if export.returncode != 0:
        raise Hold("cold export refused: " + export.stderr.strip()[-200:])
    w.note("export", result=json.loads(export.stdout.strip().splitlines()[-1]))
    forward = w.convert("jtchestconvert forward", w.dir / "donor-cold.json", "donor-input.json", "native-output.json")
    w.note("forward", rows=len(json.loads(forward.read_text(encoding="utf-8"))["rows"]), sha256=sha(forward))
    remote_snapshot, remote_artifact = f"{WORK}/window-donor-cold.json", f"{WORK}/window-native-output.json"
    w.shell(f"rm -f -- '{remote_snapshot}' '{remote_artifact}'")
    w.put(w.dir / "donor-cold.json", remote_snapshot); w.put(forward, remote_artifact)
    w.cold()
    proof = w.cli("import", "--snapshot", remote_snapshot, "--artifact", remote_artifact, "--output", f"{WORK}/window-import-proof-{time.time_ns()}.json", "--apply")
    after = w.manifest("manifest-after")
    donor = [k for k in before if k.startswith("donut_")]
    changed = [k for k in donor if before[k] != after.get(k)]
    if changed or after.get("pxc_teams_chests", {}).get("rows") != proof["count"]:
        raise Hold(f"post-import verification failed: changed={changed} native={after.get('pxc_teams_chests')}")
    w.note("verified", donorTablesUnchanged=len(donor), nativeRows=proof["count"], bankSum=after["bank_sum"])
    return f"import rows={proof['count']} donor_tables_unchanged={len(donor)} bank_sum={after['bank_sum']} dump={sha(w.dir / 'dump.sql')[:16]}"


def step_swap(w: Window, dry: bool):
    if w.target != "prd":
        raise Hold("swap is PRD-only; the dev rehearsal stages through qa/dev_mysql_rehearsal.py")
    if not dry:
        started = w.cold(); w.note("cold", startedAt=started)
    code, out, _ = w.shell(f"sha256sum {PRD}/plugins/JustTeams-2.5.5.jar; ls {PRD}/mods | grep -i -c justteams || true; test -e {PRD}/config/JustTeams && echo CFG_EXISTS || echo CFG_ABSENT; "
                           + " ".join(f"sha256sum {PRD}/plugins/justTeams/{n};" for n in YAML))
    lines = out.splitlines()
    if not lines or lines[0].split()[0] != OUTGOING or lines[1].strip() != "0" or lines[2] != "CFG_ABSENT":
        raise Hold("pre-swap state unexpected: " + " | ".join(lines[:3]))
    sources = {Path(l.split()[1]).name: l.split()[0] for l in lines[3:]}
    notice = f"{PRD}/config/pxcnotice.json"
    with w.client.open_sftp() as sftp:
        with sftp.file(notice, "r") as f:
            text = f.read().decode("utf-8")
    if text.count(NOTICE_ANCHOR) != 1 or "caller:com.pixelclub.teams." in text:
        raise Hold("pxcnotice anchor not found exactly once or rule already present")
    new_notice = text.replace(NOTICE_ANCHOR, NOTICE_ANCHOR + NOTICE_RULE)
    json.loads(new_notice)
    w.note("preflight", outgoing=OUTGOING, yaml=sources, noticeSha=hashlib.sha256(text.encode()).hexdigest())
    if dry:
        return f"swap dry-run ok outgoing={OUTGOING[:16]} yaml={len(sources)} notice_rule=insertable"
    jar = SCRATCH / "artifacts" / f"JustTeams-2.5.5+pxc.1-{INCOMING[:8]}.jar"
    if sha(jar) != INCOMING:
        raise Hold("local incoming jar mismatch")
    w.put(jar, f"{WORK}/incoming.jar")
    outgoing_remote = f"{WORK}/outgoing-JustTeams-2.5.5.jar"
    script = (f"set -e; cd {PRD}; test ! -e config/JustTeams; mkdir config/JustTeams; "
              + " ".join(f"cp -p plugins/justTeams/{n} config/JustTeams/{n};" for n in YAML)
              + f" cp -p plugins/JustTeams-2.5.5.jar {outgoing_remote}; "
              f"cp {WORK}/incoming.jar mods/JustTeams-2.5.5+pxc.1.jar.jtnew; chown --reference=plugins/JustTeams-2.5.5.jar mods/JustTeams-2.5.5+pxc.1.jar.jtnew; chmod 644 mods/JustTeams-2.5.5+pxc.1.jar.jtnew; "
              f"mv mods/JustTeams-2.5.5+pxc.1.jar.jtnew mods/JustTeams-2.5.5+pxc.1.jar; rm plugins/JustTeams-2.5.5.jar")
    w.cold()
    code, out, err = w.shell(script)
    if code != 0:
        raise Hold("swap script failed: " + err[-200:])
    with w.client.open_sftp() as sftp:
        with sftp.file(notice + ".jtnew", "w") as f:
            f.write(new_notice.encode("utf-8"))
    w.shell(f"chown --reference={notice} {notice}.jtnew && chmod --reference={notice} {notice}.jtnew && mv -f {notice}.jtnew {notice}")
    w.get(outgoing_remote, w.dir / "outgoing-JustTeams-2.5.5.jar")
    if sha(w.dir / "outgoing-JustTeams-2.5.5.jar") != OUTGOING:
        raise Hold("outgoing archive hash mismatch (still on host)")
    w.shell(f"rm -f -- {outgoing_remote} {WORK}/incoming.jar")
    code, out, _ = w.shell(f"sha256sum {PRD}/mods/JustTeams-2.5.5+pxc.1.jar; test -e {PRD}/plugins/JustTeams-2.5.5.jar && echo PLUGIN_PRESENT || echo PLUGIN_GONE; "
                           + " ".join(f"sha256sum {PRD}/config/JustTeams/{n};" for n in YAML) + f" grep -c 'caller:com.pixelclub.teams.' {notice}")
    lines = out.splitlines()
    staged = {Path(l.split()[1]).name: l.split()[0] for l in lines[2:-1]}
    if lines[0].split()[0] != INCOMING or lines[1] != "PLUGIN_GONE" or staged != sources or lines[-1].strip() != "1":
        raise Hold("post-swap verification failed: " + " | ".join(lines[:2]))
    w.cold()
    w.note("swapped", incoming=INCOMING, outgoingArchivedOffServer=str(w.dir / "outgoing-JustTeams-2.5.5.jar"), yaml=staged)
    return f"swap incoming={INCOMING[:16]} outgoing_offserver={OUTGOING[:16]} yaml=7 notice_rule=1"


def step_postboot(w: Window):
    root = PRD if w.target == "prd" else "/root/pixelclub/dev-pixelclub-server"
    code, out, _ = w.shell(f"grep -a -e '\\[pxc-teams\\]' -e 'pxc-justteams' {root}/logs/latest.log | tail -n 20")
    w.note("log", lines=out.splitlines()[-8:])
    ready = [l for l in out.splitlines() if "native 2.5.5+pxc.1 ready:" in l]
    if not ready or "ready=true" not in ready[-1] or "pending=0" not in ready[-1] or "provider=true" not in ready[-1]:
        raise Hold("native core not ready/pending>0/provider down: " + (ready[-1][-160:] if ready else "no ready line"))
    if w.target == "prd" and "team=released(vanilla wrapper)->own wrapper" not in out:
        raise Hold("/team label not released to justTeams")
    for bad in ("NOT READY", "DORMANT", "watchdog failed", "stop reconciliation required"):
        if bad in out:
            raise Hold("boot log contains " + bad)
    return "postboot " + ready[-1].split("ready:")[-1].strip()


def step_rollback(w: Window):
    started = w.cold(); w.note("cold", startedAt=started)
    before = w.manifest("manifest-before-rollback")
    export_remote = f"{WORK}/window-native-export-{time.time_ns()}.json"
    proof = w.cli("export-native", "--output", export_remote)
    w.get(export_remote, w.dir / "native-export.json")
    reverse = w.convert("jtchestconvert reverse", w.dir / "native-export.json", "native-input.json", "reverse-output.json")
    remote_reverse = f"{WORK}/window-reverse-{time.time_ns()}.json"; w.put(reverse, remote_reverse)
    w.cold()
    result = w.cli("rollback", "--snapshot", export_remote, "--artifact", remote_reverse, "--output", f"{WORK}/window-rollback-proof-{time.time_ns()}.json", "--apply")
    after = w.manifest("manifest-after-rollback")
    if after.get("pxc_teams_chests", {}).get("rows") != 0 or after["bank_sum"] != before["bank_sum"] or after["donut_teams"] != before["donut_teams"] or after["donut_team_members"] != before["donut_team_members"]:
        raise Hold("post-rollback verification failed")
    if w.target == "prd":
        archive = sorted(SCRATCH.glob("window-prd-swap-*/outgoing-JustTeams-2.5.5.jar"))
        if not archive or sha(archive[-1]) != OUTGOING:
            raise Hold("outgoing plugin archive missing")
        w.put(archive[-1], f"{WORK}/restore-plugin.jar")
        code, out, err = w.shell(f"set -e; cd {PRD}; cp {WORK}/restore-plugin.jar plugins/JustTeams-2.5.5.jar; chmod 644 plugins/JustTeams-2.5.5.jar; "
                                 f"mv mods/JustTeams-2.5.5+pxc.1.jar {WORK}/rolledback-native.jar; rm -f {WORK}/restore-plugin.jar {WORK}/rolledback-native.jar; sha256sum plugins/JustTeams-2.5.5.jar")
        if code != 0 or out.split()[0] != OUTGOING:
            raise Hold("plugin restore failed")
        w.note("unswapped", plugin=OUTGOING, note="config/JustTeams and the pxcnotice rule are inert under Bukkit and are left in place")
    return f"rollback rows={result['count']} native_rows_after=0 money_unchanged_by_rollback=true"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("step", choices=["import", "swap", "postboot", "rollback"])
    p.add_argument("--target", required=True, choices=list(TARGETS))
    p.add_argument("--dry-run", action="store_true")
    a = p.parse_args()
    w = Window(a.target, a.step)
    t0 = time.monotonic()
    try:
        summary = {"import": lambda: step_import(w), "swap": lambda: step_swap(w, a.dry_run),
                   "postboot": lambda: step_postboot(w), "rollback": lambda: step_rollback(w)}[a.step]()
        w.offload()
        w.note("done", seconds=round(time.monotonic() - t0, 1))
        print(f"justteams_window: OK {a.step} target={a.target} {summary} seconds={round(time.monotonic() - t0, 1)} evidence={w.dir}")
        return 0
    except Hold as hold:
        w.note("HOLD", reason=str(hold))
        print(f"justteams_window: HOLD DOWN {a.step} target={a.target}: {hold} evidence={w.dir}")
        return 9
    except Exception as error:
        w.note("ERROR", kind=type(error).__name__)
        print(f"justteams_window: HOLD DOWN {a.step} target={a.target}: unexpected {type(error).__name__} evidence={w.dir}")
        return 9
    finally:
        w.close()


if __name__ == "__main__":
    sys.exit(main())
