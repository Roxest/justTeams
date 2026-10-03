"""Host-side justTeams cutover helpers over the existing pxc SSH client. Credentials never leave the host.

Subcommands
  catalog-create            copy the 22 PRD `pixelclub.donut_*` tables into a NEW catalog `jt_rehearsal` (SELECT on PRD only)
  catalog-manifest <db>     row count + CHECKSUM TABLE of every `donut_%` / `pxc_teams_%` table in <db> (pixelclub|jt_rehearsal)
  catalog-drop              drop `jt_rehearsal` (refuses any other name)
  props <db>                write /root/pixelclub/ops/secrets/jt-<db>.properties (0600) for the ColdChests CLI
  dump <db> <remote.sql>    mysqldump of every donut_% table via the MySQL container (root env inside the container)
  upload <local> <remote>   sftp a file into /root/pixelclub/ops/justteams-cutover/ (refuses other dirs)
  download <remote> <local> sftp a file out of /root/pixelclub/ops/justteams-cutover/
  remove <remote>           delete one file under /root/pixelclub/ops/justteams-cutover/
  cli <args...>             run ColdChests with host Java 21 and the uploaded classpath; prints CLI stdout/stderr only
The account comes from PRD plugins/justTeams/config.yml (storage.mysql.*), read on the host inside the remote script.
"""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

PXC = Path("F:/Pixelclub/tools/pxc.py")
WORK = "/root/pixelclub/ops/justteams-cutover"
SECRETS = "/root/pixelclub/ops/secrets"
JAVA = "/root/pixelclub/ops/jdk21/bin/java"
CONFIG = "/root/pixelclub/pixelclub-cobblemon-server/plugins/justTeams/config.yml"
CLASSPATH = f"{WORK}/JustTeams-PXC-FINAL.jar:{WORK}/JustTeams-Fabric-2.5.5.jar:{WORK}/gson-2.10.1.jar"

PRELUDE = r'''
import ssl, json, sys, yaml
if not hasattr(ssl, "wrap_socket"):
    def _w(sock, keyfile=None, certfile=None, ca_certs=None, cert_reqs=None, ssl_version=None, do_handshake_on_connect=True, **kw):
        c = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); c.check_hostname = False; c.verify_mode = ssl.CERT_NONE
        return c.wrap_socket(sock, do_handshake_on_connect=do_handshake_on_connect)
    ssl.wrap_socket = _w
import mysql.connector
CFG = yaml.safe_load(open("CONFIG_PATH", encoding="utf-8"))["storage"]["mysql"]
def connect(database):
    return mysql.connector.connect(host="127.0.0.1", port=int(CFG["port"]), user=str(CFG["username"]), password=str(CFG["password"]),
                                   database=database, use_pure=True, ssl_disabled=True, connection_timeout=10)
'''.replace("CONFIG_PATH", CONFIG)

BODIES = {
"catalog-create": r'''
c = connect("pixelclub"); cur = c.cursor()
cur.execute("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='jt_rehearsal'")
if cur.fetchone(): raise SystemExit("jt_rehearsal already exists")
cur.execute("SHOW TABLES LIKE 'donut\\_%'"); tables = sorted(r[0] for r in cur.fetchall())
if len(tables) != 22: raise SystemExit(f"expected 22 donor tables, found {len(tables)}")
cur.execute("CREATE DATABASE jt_rehearsal CHARACTER SET utf8mb4")
for t in tables:
    cur.execute(f"CREATE TABLE jt_rehearsal.`{t}` LIKE pixelclub.`{t}`")
    cur.execute(f"INSERT INTO jt_rehearsal.`{t}` SELECT * FROM pixelclub.`{t}`")
c.commit(); print(json.dumps({"created": "jt_rehearsal", "tables": len(tables)}))
''',
"catalog-manifest": r'''
db = ARG1
if db not in ("pixelclub", "jt_rehearsal"): raise SystemExit("unknown catalog")
c = connect(db); cur = c.cursor(); cur.execute("SET SESSION TRANSACTION READ ONLY"); cur.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
cur.execute("SHOW TABLES"); tables = sorted(r[0] for r in cur.fetchall() if r[0].startswith(("donut_", "pxc_teams_")))
out = {}
for t in tables:
    cur.execute(f"SELECT COUNT(*) FROM `{t}`"); n = cur.fetchone()[0]
    cur.execute(f"CHECKSUM TABLE `{t}`"); out[t] = {"rows": n, "checksum": cur.fetchone()[1]}
cur.execute("SELECT ROUND(SUM(balance),3), COUNT(*) FROM donut_teams"); s = cur.fetchone(); out["bank_sum"] = str(s[0])
cur.execute("SELECT COUNT(*) FROM donut_team_enderchest WHERE inventory_data IS NOT NULL AND inventory_data<>''"); out["donor_chests"] = cur.fetchone()[0]
c.rollback(); print(json.dumps(out, sort_keys=True))
''',
"dump": r'''
import os, subprocess, hashlib
db, target = json.loads(ARG1)
if db not in ("pixelclub", "jt_rehearsal") or not target.startswith("WORKDIR/"): raise SystemExit("refused")
c = connect(db); cur = c.cursor(); cur.execute("SHOW TABLES"); tables = sorted(r[0] for r in cur.fetchall() if r[0].startswith(("donut_", "pxc_teams_"))); c.close()
os.makedirs("WORKDIR", mode=0o700, exist_ok=True)
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "wb") as out:
    r = subprocess.run(["docker", "exec", "pixelclub-mysql", "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --skip-lock-tables --hex-blob --no-tablespaces "$0" "$@"', db, *tables], stdout=out, stderr=subprocess.PIPE)
if r.returncode != 0: raise SystemExit("mysqldump exit " + str(r.returncode))
data = open(target, "rb").read()
print(json.dumps({"tables": len(tables), "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "inserts": data.count(b"INSERT INTO ")}))
'''.replace("WORKDIR", WORK),
"catalog-drop": r'''
c = connect("pixelclub"); cur = c.cursor(); cur.execute("DROP DATABASE IF EXISTS jt_rehearsal"); print(json.dumps({"dropped": "jt_rehearsal"}))
''',
"props": r'''
import os
db = ARG1
if db not in ("pixelclub", "jt_rehearsal"): raise SystemExit("unknown catalog")
path = f"SECRETS/jt-{db}.properties"
def esc(v): return str(v).replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=").replace("\n", "")
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f:
    f.write("jdbc_url=" + esc(f"jdbc:mysql://127.0.0.1:{int(CFG['port'])}/{db}?useSSL=false&serverTimezone=UTC") + "\n")
    f.write("username=" + esc(CFG["username"]) + "\npassword=" + esc(CFG["password"]) + "\ndriver=com.mysql.cj.jdbc.Driver\nprefix=donut_\n")
os.chmod(path, 0o600); print(json.dumps({"properties": path, "mode": "0600"}))
'''.replace("SECRETS", SECRETS),
}


def module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec); spec.loader.exec_module(loaded); return loaded


def remote_python(client, body: str, arg: str | None = None, timeout=600):
    script = PRELUDE + "\nARG1 = json.loads(" + repr(json.dumps(arg)) + ")\n" + body
    stdin, out, err = client.exec_command("python3 -", timeout=timeout)
    stdin.write(script); stdin.flush(); stdin.channel.shutdown_write()
    data, error = out.read().decode(), err.read().decode()
    if out.channel.recv_exit_status() != 0:
        # Remote exceptions can echo connection settings; keep only the exception type line.
        last = [l for l in error.strip().splitlines() if l][-1:] or ["unknown"]
        kind = last[0].split(":")[0][:120]
        # NameError/SyntaxError text is code, not data; anything else stays withheld.
        raise SystemExit("remote failed: " + (last[0][:200] if kind in ("NameError", "SyntaxError", "SystemExit") else kind))
    return data.strip()


def shell(client, command: str, timeout=600):
    _, out, err = client.exec_command(command, timeout=timeout)
    data, error = out.read().decode(errors="replace"), err.read().decode(errors="replace")
    return out.channel.recv_exit_status(), data.strip(), error.strip()


def under_work(path: str) -> str:
    if not path.startswith(WORK + "/") or ".." in path:
        raise SystemExit("remote path must be under " + WORK)
    return path


def main(argv):
    if not argv:
        raise SystemExit(__doc__)
    client = module(PXC, "jt_pxc").connect()
    try:
        cmd = argv[0]
        if cmd in BODIES and cmd != "dump":
            print(remote_python(client, BODIES[cmd], argv[1] if len(argv) > 1 else None))
        elif cmd == "dump":
            print(remote_python(client, BODIES["dump"], json.dumps([argv[1], under_work(argv[2])]), timeout=900))
        elif cmd == "upload":
            target = under_work(argv[2])
            shell(client, f"mkdir -p {WORK} && chmod 700 {WORK}")
            with client.open_sftp() as sftp:
                sftp.put(argv[1], target); sftp.chmod(target, 0o600)
            print(shell(client, f"sha256sum {target}")[1])
        elif cmd == "download":
            if Path(argv[2]).exists():
                raise SystemExit("local target exists")
            with client.open_sftp() as sftp:
                sftp.stat(under_work(argv[1]))
                sftp.get(argv[1], argv[2])
            print(json.dumps({"downloaded": argv[2]}))
        elif cmd == "remove":
            print(shell(client, f"rm -f -- {under_work(argv[1])} && echo removed")[1])
        elif cmd == "cli":
            args = " ".join(a if a.replace("-", "").replace("_", "").replace("/", "").replace(".", "").isalnum() else "'" + a + "'" for a in argv[1:])
            code, out, err = shell(client, f"{JAVA} -cp {CLASSPATH} com.pixelclub.teams.tools.ColdChests {args}", timeout=900)
            print(json.dumps({"exit": code, "stdout": out, "stderr": err[-400:]}))
            if code != 0:
                raise SystemExit(1)
        else:
            raise SystemExit("unknown subcommand " + cmd)
    finally:
        client.close()


if __name__ == "__main__":
    main(sys.argv[1:])
