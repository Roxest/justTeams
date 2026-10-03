"""Private fixed-source donor stage; no runtime placement, commands or DB connection."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timezone
import yaml

SOURCE = "/root/pixelclub/pixelclub-cobblemon-server/plugins/justTeams/"
TARGET = Path("F:/Pixelclub/scratch/justteams-cutover-20261002/private-gui-stage")
FILES = ("config.yml", "gui.yml", "messages.yml", "commands.yml", "placeholders.yml", "quests.yml")
SENSITIVE = ("licence", "license", "password", "passwd", "token", "secret", "webhook", "apikey", "accesskey")
UI_LABELS = {
    "messages.yml": {"invalid_warp_password", "team_chat_password_warning", "warp_password_protected", "warp_incorrect_password", "prompt_warp_password", "warp_password_required", "warp_password_incorrect"},
    "placeholders.yml": {"warps.password_protected_label"},
}
EXPECTED_SOURCE = {
    "config.yml": "13b9c9cfbd26d2053fb54ce40244fe5209ba33261cde77703e35e92fe62e0095",
    "commands.yml": "2ef9edec478b0def636b1698a2058cd4f61b7ba232cc6712e3adbf068cb6a23c",
    "gui.yml": "4aed3365e6af784b9470ce6490473d30020680ca23570abfcb777e95b17786e8",
    "messages.yml": "3dcb5c3f9ed56f08be2b52803c4591c587bb768089c37a7b4fb620dfb92cb125",
    "placeholders.yml": "e8132174ae24623f8cf4b887ca5884ed3b5fe37c291059b89e5ceee987059a84",
    "quests.yml": "5d27cdf0c40e144820b2377412560134c467ae8ffdf03976ad67127bc4b26557",
}
ENDPOINT = re.compile(r"(?i)(?:https?://[^\s]*?(?:/api(?:/v\d+)?/webhooks/|[?&](?:token|secret|password|api[_-]?key)=)|\bbearer\s+[A-Za-z0-9._~-]+|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)")

class Refused(Exception):
    def __init__(self, paths): self.paths = paths

def normalized(value): return re.sub(r"[^a-z0-9]", "", str(value).lower())
def suspect_key(key): return any(word in normalized(key) for word in SENSITIVE)
def scan_keys(value, prefix=""):
    found = []
    if isinstance(value, dict):
        for key, child in value.items():
            path = prefix + ("." if prefix else "") + str(key)
            if suspect_key(key): found.append({"path": path, "type": type(child).__name__})
            found.extend(scan_keys(child, path))
    elif isinstance(value, list):
        for index, child in enumerate(value): found.extend(scan_keys(child, prefix + "." + str(index)))
    return found

def at(data, path):
    for key in path.split("."):
        if not isinstance(data, dict): return None
        data = data.get(key)
    return data

def strings(value):
    if isinstance(value, str): yield value
    elif isinstance(value, dict):
        for child in value.values(): yield from strings(child)
    elif isinstance(value, list):
        for child in value: yield from strings(child)

def credentials(config):
    values = set()
    def walk(value, connection=False):
        if not isinstance(value, dict): return
        for key, child in value.items():
            conn = connection or normalized(key) in {"storage", "mysql", "redis", "database", "jdbc"}
            if isinstance(child, str) and child and (suspect_key(key) or conn and normalized(key) in {"username", "user", "host", "hostname", "database", "url", "uri", "endpoint"}): values.add(child)
            walk(child, conn)
    walk(config); return values

def unsafe_values(value, known):
    return any(ENDPOINT.search(text) or any(secret in text for secret in known) for text in strings(value))

def private_directory():
    if os.name != "nt" or not TARGET.is_absolute(): raise Refused(["stage.path"])
    if TARGET.exists() and (not TARGET.is_dir() or any(TARGET.iterdir())): raise Refused(["stage.path"])
    TARGET.mkdir(parents=True, exist_ok=True)
    # Fixed local path, ASCII PowerShell only; no source content/credential is interpolated.
    command = """
$ErrorActionPreference='Stop'
$guiStagePath='F:/Pixelclub/scratch/justteams-cutover-20261002/private-gui-stage'
$guiStageOwner=[System.Security.Principal.WindowsIdentity]::GetCurrent().User
$guiStageAcl=[System.IO.Directory]::GetAccessControl($guiStagePath)
$guiStageAcl.SetAccessRuleProtection($true,$false)
foreach($guiStageOldRule in @($guiStageAcl.GetAccessRules($true,$false,[System.Security.Principal.SecurityIdentifier]))){$guiStageAcl.RemoveAccessRuleSpecific($guiStageOldRule)}
$guiStageRule=[System.Security.AccessControl.FileSystemAccessRule]::new($guiStageOwner,[System.Security.AccessControl.FileSystemRights]::FullControl,([System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit),[System.Security.AccessControl.PropagationFlags]::None,[System.Security.AccessControl.AccessControlType]::Allow)
$guiStageAcl.AddAccessRule($guiStageRule)
[System.IO.Directory]::SetAccessControl($guiStagePath,$guiStageAcl)
$guiStageCheck=[System.IO.Directory]::GetAccessControl($guiStagePath)
if(-not $guiStageCheck.AreAccessRulesProtected){exit 3}
$guiStageRules=@($guiStageCheck.GetAccessRules($true,$false,[System.Security.Principal.SecurityIdentifier]))
if($guiStageRules.Count -ne 1){exit 4}
if($guiStageRules[0].IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -ne $guiStageOwner.Value){exit 5}
exit 0
"""
    command = "try {" + command + "} catch { $guiStageError=$_.Exception; while($guiStageError.InnerException){$guiStageError=$guiStageError.InnerException}; [Console]::WriteLine('ACL_TYPE=' + $guiStageError.GetType().Name); exit 9 }"
    completed = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command], stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=0x08000000)
    if completed.returncode != 0:
        diagnostic = completed.stdout.decode("ascii", errors="ignore").strip()
        safe_type = diagnostic if re.fullmatch(r"ACL_TYPE=[A-Za-z0-9]+", diagnostic) else "unavailable"
        raise Refused(["stage.owner_acl." + str(completed.returncode) + "." + safe_type])

def stage(blobs, docs, metadata):
    for name in FILES:
        if metadata[name]["source_sha256"] != EXPECTED_SOURCE[name]: raise Refused([name + ".source_drift"])
    known = credentials(docs["config.yml"])
    for name in FILES[1:]:
        for candidate in scan_keys(docs[name]):
            if candidate["path"] not in UI_LABELS.get(name, set()) or candidate["type"] != "str": raise Refused([name + "." + candidate["path"]])
        if unsafe_values(docs[name], known) or ENDPOINT.search(blobs[name].decode("utf-8")) or any(secret in blobs[name].decode("utf-8") for secret in known): raise Refused([name + ".credential_or_endpoint_value"])
    original = docs["config.yml"]
    sanitized = copy.deepcopy(original)
    sanitized["storage"] = {"type": "h2", "table_prefix": "donut_"}
    sanitized["redis"] = {"enabled": False}
    removed = []
    def prune(value, prefix=""):
        if not isinstance(value, dict): return
        for key in list(value):
            path = prefix + ("." if prefix else "") + str(key)
            if suspect_key(key): removed.append(path); del value[key]
            else: prune(value[key], path)
    prune(sanitized)
    if scan_keys(sanitized) or unsafe_values(sanitized, known): raise Refused(["config.sanitization"])
    before = {key: value for key, value in original.items() if key not in {"storage", "redis"}}
    after = {key: value for key, value in sanitized.items() if key not in {"storage", "redis"}}
    if before != after or removed: raise Refused(["config.nonisolation_diff"])
    payload = yaml.safe_dump(sanitized, allow_unicode=True, sort_keys=False).encode("utf-8")
    if yaml.safe_load(payload.decode("utf-8")) != sanitized: raise Refused(["config.roundtrip"])
    private_directory()
    outputs = {}
    for name in FILES:
        content = payload if name == "config.yml" else blobs[name]
        parsed = yaml.safe_load(content.decode("utf-8"))
        if not isinstance(parsed, dict) or unsafe_values(parsed, known): raise Refused([name + ".final_validation"])
        with (TARGET / name).open("xb") as stream: stream.write(content)
        outputs[name] = {"sha256": hashlib.sha256(content).hexdigest(), "bytes": len(content), "exact_source_bytes": name != "config.yml"}
    report = {"prepared_utc": datetime.now(timezone.utc).isoformat(), "source": metadata, "outputs": outputs, "owner_only_acl": True, "runtime_placement": False, "config_override_paths": ["storage", "redis"], "native_h2_file_base": "data/teams-pxc", "behavior_unchanged": True, "integer_tier_keys_preserved": all(isinstance(key, int) for key in sanitized.get("team_upgrades", {}).get("tiers", {})), "ui_label_exceptions": UI_LABELS}
    # Convert explicitly approved sets to metadata arrays, never include source values.
    report["ui_label_exceptions"] = {name: sorted(paths) for name, paths in UI_LABELS.items()}
    with (TARGET / "sanitization-report.json").open("x", encoding="utf-8") as stream: json.dump(report, stream, ensure_ascii=True, separators=(",", ":"))
    print(json.dumps({"stage": str(TARGET), "files": 6, "sanitized_config_sha256": outputs["config.yml"]["sha256"], "nonconfig_exact": True, "override_paths": ["storage", "redis"], "owner_only_acl": True, "runtime_placement": False}, separators=(",", ":")))

def fetch():
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        spec = importlib.util.spec_from_file_location("pxc_gui_stage", "F:/Pixelclub/tools/pxc.py")
        pxc = importlib.util.module_from_spec(spec); spec.loader.exec_module(pxc); client = pxc.connect()
    try:
        sftp = client.open_sftp(); blobs, docs, metadata = {}, {}, {}
        for name in FILES:
            stat = sftp.stat(SOURCE + name)
            with sftp.open(SOURCE + name, "rb") as stream: content = stream.read()
            blobs[name] = content; docs[name] = yaml.safe_load(content.decode("utf-8"))
            if not isinstance(docs[name], dict): raise ValueError()
            metadata[name] = {"source_sha256": hashlib.sha256(content).hexdigest(), "source_modified_utc": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat()}
        sftp.close(); return blobs, docs, metadata
    finally: client.close()

def main():
    try:
        blobs, docs, metadata = fetch()
        if sys.argv[1:] == ["--inspect-only"]:
            print(json.dumps({name: scan_keys(data) for name, data in docs.items()}, separators=(",", ":")))
            return 0
        if sys.argv[1:] == ["--apply"]: stage(blobs, docs, metadata); return 0
        raise ValueError()
    except Refused as refusal:
        print(json.dumps({"refused_paths": refusal.paths}, separators=(",", ":"))); return 1
    except Exception:
        print("PRIVATE_GUI_STAGE_REFUSED"); return 1

if __name__ == "__main__": sys.exit(main())
