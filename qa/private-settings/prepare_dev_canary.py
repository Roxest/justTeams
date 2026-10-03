"""Read-only DEV preflight and exclusive private off-server canary staging."""
import contextlib
from datetime import datetime, timezone
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import yaml

TARGET = Path("F:/Pixelclub/scratch/justteams-cutover-20261002/private-dev-canary-20261003")
STAGE = Path("F:/Pixelclub/scratch/justteams-cutover-20261002/private-gui-stage")
ARTIFACTS = Path("F:/Pixelclub/scratch/justteams-cutover-20261002/artifacts")
DEV = "/root/pixelclub/dev-pixelclub-server/"
FILES = ("config.yml", "gui.yml", "messages.yml", "commands.yml", "placeholders.yml", "quests.yml")
ORIGINAL_SHA = "162dfd8c551497d08fcfe64dbbcc09437812a8ca1ae3eddf442408e6cf72e86a"
NATIVE_SHA = "6864630f29a2b33cc0299e3cfcbca4335a1916e459c8208b8460165835567487"
BASE_STARTED = "2026-10-03T01:52:09.51251204Z"
BASE_HASHES = {"mods/pxctools-1.0.0.jar": "a7092a77b760e5b6f40467879d952d69f8ab889b296726f3c73f5e726c5d725c", "plugins/DonutTools-3.0.jar": "565a0b0443eff690a46a1db296e3aaac182d0909e8ccb9947bf7f91516fa3db5"}

class Refused(Exception):
    pass

def sha(content):
    return hashlib.sha256(content).hexdigest()

def utc(seconds):
    return datetime.fromtimestamp(seconds, timezone.utc).isoformat()

def private_root():
    if os.name != "nt" or TARGET.exists() and (not TARGET.is_dir() or any(TARGET.iterdir())):
        raise Refused("target_must_be_new_local_directory")
    TARGET.mkdir(parents=True, exist_ok=True)
    # Constant ASCII path; no remote bytes or secrets enter shell command text.
    command = r"""$ErrorActionPreference='Stop'
$canaryPath='F:/Pixelclub/scratch/justteams-cutover-20261002/private-dev-canary-20261003'
$canarySid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User
$canaryAcl=[System.IO.Directory]::GetAccessControl($canaryPath)
$canaryAcl.SetAccessRuleProtection($true,$false)
foreach($canaryRule in @($canaryAcl.GetAccessRules($true,$false,[System.Security.Principal.SecurityIdentifier]))){$canaryAcl.RemoveAccessRuleSpecific($canaryRule)}
$canaryAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($canarySid,[System.Security.AccessControl.FileSystemRights]::FullControl,([System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit),[System.Security.AccessControl.PropagationFlags]::None,[System.Security.AccessControl.AccessControlType]::Allow))
[System.IO.Directory]::SetAccessControl($canaryPath,$canaryAcl)
$canaryCheck=[System.IO.Directory]::GetAccessControl($canaryPath)
$canaryRules=@($canaryCheck.GetAccessRules($true,$false,[System.Security.Principal.SecurityIdentifier]))
if(-not $canaryCheck.AreAccessRulesProtected -or $canaryRules.Count -ne 1 -or $canaryRules[0].IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -ne $canarySid.Value){exit 3}
"""
    command = "try {" + command + "} catch { [Console]::WriteLine('ACL_TYPE=' + $_.Exception.GetType().Name); exit 9 }"
    result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command], capture_output=True, creationflags=0x08000000)
    if result.returncode:
        diagnostic = result.stdout.decode("ascii", errors="ignore").strip()
        safe_type = diagnostic if re.fullmatch(r"ACL_TYPE=[A-Za-z0-9]+", diagnostic) else "unavailable"
        raise Refused("owner_only_acl_failed_" + str(result.returncode) + "_" + safe_type)

def exclusive(relative, content):
    path = TARGET / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(content)

def doc(content):
    try:
        value = yaml.safe_load(content.decode("utf-8-sig"))
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except Exception:
        raise Refused("yaml_parse_refused") from None

def forbidden_licence(value):
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = re.sub(r"[^a-z0-9]", "", str(key).lower())
            if ("license" in normalized or "licence" in normalized) and child not in (None, "", False):
                return True
            if forbidden_licence(child):
                return True
    elif isinstance(value, list):
        return any(forbidden_licence(child) for child in value)
    return False

def stable_read(sftp, path):
    before = sftp.stat(path)
    with sftp.open(path, "rb") as stream:
        content = stream.read()
    after = sftp.stat(path)
    if (before.st_size, before.st_mtime) != (after.st_size, after.st_mtime) or len(content) != before.st_size:
        raise Refused("source_changed_during_read")
    return content, {"sha256": sha(content), "bytes": len(content), "modified_utc": utc(before.st_mtime)}

def docker_state(client, container):
    command = "docker inspect --format '{{json .State}}' " + container
    _, stdout, stderr = client.exec_command(command)
    raw = stdout.read()
    stderr.read()
    if stdout.channel.recv_exit_status():
        raise Refused("docker_metadata_unavailable")
    state = json.loads(raw)
    return {key: state.get(key) for key in ("Running", "Restarting", "Paused", "StartedAt", "Pid", "OOMKilled")}

def prepare():
    local_report = json.loads((STAGE / "sanitization-report.json").read_text(encoding="utf-8"))
    staged = {name: (STAGE / name).read_bytes() for name in FILES}
    for name, content in staged.items():
        if sha(content) != local_report["outputs"][name]["sha256"] or forbidden_licence(doc(content)):
            raise Refused("sanitized_stage_drift_or_licence")
    config = doc(staged["config.yml"])
    if config.get("storage") != {"type": "h2", "table_prefix": "donut_"} or config.get("redis") != {"enabled": False}:
        raise Refused("local_storage_isolation_mismatch")
    native = (ARTIFACTS / "JustTeams-2.5.5+pxc.1-6864630f.jar").read_bytes()
    original = (ARTIFACTS / "JustTeams-Fabric-2.5.5.jar").read_bytes()
    if sha(native) != NATIVE_SHA or sha(original) != ORIGINAL_SHA:
        raise Refused("pinned_local_artifact_drift")
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        spec = importlib.util.spec_from_file_location("canary_pxc", "F:/Pixelclub/tools/pxc.py")
        pxc = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pxc)
        client = pxc.connect()
    try:
        before = docker_state(client, "pixelclub-arclight-dev")
        if before["StartedAt"] != BASE_STARTED or before["Pid"] != 3724316 or not before["Running"] or before["Restarting"] or before["Paused"]:
            raise Refused("dev_baseline_state_changed")
        prd = docker_state(client, "pixelclub-arclight")
        sftp = client.open_sftp()
        try:
            mods = [{"filename": a.filename, "bytes": a.st_size, "modified_utc": utc(a.st_mtime)} for a in sftp.listdir_attr(DEV + "mods") if a.filename.endswith(".jar")]
            candidates = [m for m in mods if "justteams" in m["filename"].lower()]
            if len(candidates) != 1:
                raise Refused("dev_original_mod_count_mismatch")
            remote_original, original_meta = stable_read(sftp, DEV + "mods/" + candidates[0]["filename"])
            if original_meta["sha256"] != ORIGINAL_SHA:
                print(json.dumps({"dev_original_filename": candidates[0]["filename"], "dev_original_metadata": original_meta, "expected_original_sha256": ORIGINAL_SHA}, separators=(",", ":")))
            # Rollback captures the actual baseline. Mismatch is a review gate,
            # never an authorization to substitute the pinned standalone driver.
            original_meta["matches_pinned_original"] = original_meta["sha256"] == ORIGINAL_SHA
            baseline = {}
            for relative, expected in BASE_HASHES.items():
                content, metadata = stable_read(sftp, DEV + relative)
                if metadata["sha256"] != expected:
                    raise Refused("dev_tools_baseline_hash_changed")
                baseline[relative] = metadata
            backups, metadata = {}, {}
            for name in FILES:
                content, metadata[name] = stable_read(sftp, DEV + "config/JustTeams/" + name)
                parsed = doc(content)
                if forbidden_licence(parsed):
                    raise Refused("rollback_contains_licence")
                if name == "config.yml":
                    metadata[name]["storage_type"] = parsed.get("storage", {}).get("type") if isinstance(parsed.get("storage"), dict) else None
                    metadata[name]["storage_mapping_type"] = type(parsed.get("storage")).__name__
                backups[name] = content
        finally:
            sftp.close()
        after = docker_state(client, "pixelclub-arclight-dev")
        if after != before:
            raise Refused("dev_state_changed_during_preflight")
    finally:
        client.close()
    private_root()
    for name in FILES:
        exclusive("deploy/config/JustTeams/" + name, staged[name])
        exclusive("rollback/config/JustTeams/" + name, backups[name])
    exclusive("deploy/mods/JustTeams-2.5.5+pxc.1.jar", native)
    exclusive("bootstrap/JustTeams-Fabric-2.5.5.jar", original)
    exclusive("rollback/mods/" + candidates[0]["filename"], remote_original)
    exclusive("deploy/.justteams-synthetic-fixture", b"Owned four-actor synthetic fixture only; no source runtime database copied.\n")
    report = {"prepared_utc": datetime.now(timezone.utc).isoformat(), "read_only_remote": True, "owner_only_acl": True, "dev_before": before, "dev_after": after, "prd_state_metadata": prd, "mods_metadata": mods, "baseline_hashes": baseline, "original_mod": {"filename": candidates[0]["filename"], **original_meta}, "rollback_configs": metadata, "deploy_configs": local_report["outputs"], "native_sha256": NATIVE_SHA, "original_driver_sha256": ORIGINAL_SHA, "database_source": "fresh_fixture_pending", "server_placement": False, "licence_copies": False, "rollback_private_never_boot": True}
    exclusive("preflight-private.json", json.dumps(report, ensure_ascii=True, indent=2).encode("utf-8"))
    print(json.dumps({"private_kit": str(TARGET), "dev_baseline_stable": True, "original_mod": candidates[0]["filename"], "original_sha256": original_meta["sha256"], "matches_pinned_original": original_meta["matches_pinned_original"], "native_sha256": NATIVE_SHA, "six_configs_present": True, "storage_type": metadata["config.yml"].get("storage_type"), "owner_only_acl": True, "server_placement": False}, separators=(",", ":")))

if __name__ == "__main__":
    try:
        prepare()
    except Refused as failure:
        print("CANARY_PREP_REFUSED:" + str(failure))
        sys.exit(1)
    except Exception:
        print("CANARY_PREP_REFUSED:unclassified_no_source_details")
        sys.exit(1)
