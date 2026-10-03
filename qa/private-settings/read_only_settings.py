"""Fixed donor SFTP reads: hashes and explicit safe projection only; no private bytes are saved."""
import contextlib
import hashlib
import importlib.util
import io
import json
import sys
from datetime import datetime, timezone
import yaml

BASE = "/root/pixelclub/pixelclub-cobblemon-server/plugins/justTeams/"
FILES = ("config.yml", "commands.yml", "gui.yml", "messages.yml", "placeholders.yml", "quests.yml", "settings.yml")

def get(data, dotted):
    for key in dotted.split("."):
        if not isinstance(data, dict): return None
        data = data.get(key, data.get(int(key)) if key.isdecimal() else None)
    return data

def pick(data, names):
    return {name: get(data, name) for name in names if get(data, name) is not None}

def sha_sftp(sftp, path):
    digest = hashlib.sha256()
    with sftp.open(path, "rb") as stream:
        for block in iter(lambda: stream.read(65536), b""): digest.update(block)
    return digest.hexdigest()

def duplicate_count(node):
    if isinstance(node, yaml.MappingNode):
        seen, duplicates = set(), 0
        for key, value in node.value:
            identity = (key.tag, key.value)
            duplicates += identity in seen
            seen.add(identity)
            duplicates += duplicate_count(value)
        return duplicates
    if isinstance(node, yaml.SequenceNode): return sum(duplicate_count(value) for value in node.value)
    return 0

def main():
    client = None
    try:
        # Suppress connector diagnostics rather than relay possible private connection details.
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            spec = importlib.util.spec_from_file_location("pxc_readonly", "F:/Pixelclub/tools/pxc.py")
            pxc = importlib.util.module_from_spec(spec); spec.loader.exec_module(pxc)
            client = pxc.connect()
        sftp = client.open_sftp()
        metadata, docs = {}, {}
        for name in FILES:
            try: stat = sftp.stat(BASE + name)
            except FileNotFoundError:
                metadata[name] = {"exists": False}; continue
            with sftp.open(BASE + name, "rb") as stream: content = stream.read()
            metadata[name] = {"exists": True, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest(), "modified_utc": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(), "duplicate_key_count": duplicate_count(yaml.compose(content.decode("utf-8")))}
            docs[name] = yaml.safe_load(content.decode("utf-8")) or {}
        config = docs["config.yml"]
        result = {"read_utc": datetime.now(timezone.utc).isoformat(), "file_state_only": True, "files": metadata}
        # At most 20 safe key groups; no storage host/database/user/password, licence or webhook values.
        result["safe"] = {
            "storage.type": get(config, "storage.type"),
            "team_bank": pick(config, ("team_bank.enabled", "team_bank.max_balance")),
            "feature_costs.gates": pick(config, ("feature_costs.enabled", "feature_costs.economy.enabled", "feature_costs.items.enabled")),
            "feature_costs.economy": pick(config, tuple("feature_costs.economy." + key for key in ("team_creation", "team_join", "rename", "sethome", "home", "setwarp", "warp", "enderchest", "bank_deposit", "bank_withdraw"))),
            "feature_costs.join_permission": pick(config, ("feature_costs.permission_based_join_fee.enabled", "feature_costs.permission_based_join_fee.max_fee")),
            "settings.capacity": pick(config, ("settings.max_team_size", "settings.permission_based_team_size")),
            "settings.creation": pick(config, ("settings.creation.require_color", "settings.creation.default_color", "settings.creation.use_gui_color_selection", "settings.creation.tag_optional")),
            "settings.rename_cooldown": get(config, "settings.rename_cooldown"),
            "settings.glow": pick(config, ("settings.glow.enabled", "settings.glow.only_show_own_team", "settings.glow.range", "settings.glow.check_interval", "settings.glow.use_team_color", "settings.glow.colors.owner", "settings.glow.colors.co_owner", "settings.glow.colors.member")),
            "team_upgrades.enabled": get(config, "team_upgrades.enabled"),
            "team_upgrades.tier_costs": {"key_types": sorted({type(key).__name__ for key in (get(config, "team_upgrades.tiers") or {})}), "costs": {str(tier): get(config, "team_upgrades.tiers." + str(tier) + ".cost") for tier in range(2, 6)}},
            "team_enderchest": pick(config, ("team_enderchest.enabled", "team_enderchest.rows")),
            "team_warps": pick(config, ("team_warps.enabled", "team_warps.max_warps")),
            "world_restrictions.enabled": get(config, "world_restrictions.enabled"),
            "world_restrictions.disabled_worlds": get(config, "world_restrictions.disabled_worlds"),
            "features": {key: value for key, value in (config.get("features") or {}).items() if isinstance(value, bool)},
            "commands.aliases": {key: get(docs["commands.yml"], "command-aliases." + key + ".enabled") for key in ("guild", "clan", "party", "guildmsg", "clanmsg", "partymsg")},
            "placeholders.team_display": pick(docs["placeholders.yml"], ("team_display.format", "team_display.team_color", "team_display.tag_color", "team_display.show_tag", "team_display.show_icon")),
            "quests.settings.enabled": get(docs["quests.yml"], "settings.enabled"),
            "gui.action_set": sorted({str(value.get("action")) for section in docs["gui.yml"].values() if isinstance(section, dict) for value in (section.get("items") or {}).values() if isinstance(value, dict) and value.get("action") in {"allies", "warps", "join-requests", "bank", "team-settings", "leaderboards", "home", "ender-chest", "pvp-toggle", "upgrades-button", "leave-button", "disband-button", "sort"}}),
        }
        jars = {}
        parent = "/root/pixelclub/pixelclub-cobblemon-server/plugins/"
        for name in sftp.listdir(parent):
            if "justteams" in name.lower() and name.lower().endswith(".jar"):
                stat = sftp.stat(parent + name)
                jars[name] = {"sha256": sha_sftp(sftp, parent + name), "modified_utc": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat()}
        result["source_jars_disk"] = jars
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        sftp.close()
    except Exception:
        print("READ_ONLY_SETTINGS_REFUSED")
        return 1
    finally:
        if client is not None: client.close()
    return 0

if __name__ == "__main__": sys.exit(main())
