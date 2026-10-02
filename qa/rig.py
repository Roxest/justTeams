"""Own-loopback-only native JustTeams runtime harness; never reads server configs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time
import zipfile

ROOT = Path('F:/Pixelclub/justteams-native-rig-20261003')
SOURCE = Path('F:/Pixelclub/chest-codec-rig-fabric-20261002-proof')
PROJECT = Path('F:/Pixelclub/justteams-fabric-pxc')
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin/java.exe')
MARKER = '.justteams-synthetic-fixture'
LAUNCHER = 'fabric-server-mc.1.21.1-loader.0.19.2-launcher.1.1.2.jar'

def marked():
    assert ROOT.resolve() == ROOT and ROOT.drive == 'F:' and (ROOT / MARKER).is_file()
    return ROOT

def prepare(candidate):
    if ROOT.exists():
        marked()
        assert not (ROOT / 'runtime-manifest.json').exists() and not (ROOT / 'qa-server.pid').exists(), 'Refuse completed/running rig'
    for port in (25655, 25656):
        with socket.socket() as check:
            check.bind(('127.0.0.1', port))
    ROOT.mkdir(exist_ok=True)
    (ROOT / MARKER).write_text('synthetic-native-runtime-fixture\n', encoding='utf-8')
    for folder in ('libraries', 'mods'):
        shutil.copytree(SOURCE / folder, ROOT / folder, dirs_exist_ok=True)
    shutil.copy2(SOURCE / LAUNCHER, ROOT / LAUNCHER)
    excluded = []
    for jar in (ROOT / 'mods').glob('*.jar'):
        with zipfile.ZipFile(jar) as archive:
            if 'fabric.mod.json' not in archive.namelist():
                continue
            metadata = archive.read('fabric.mod.json').decode('utf-8')
            mod_id = re.search(r'"id"\s*:\s*"([^"]+)"', metadata)
            if mod_id and mod_id.group(1) in ('justteams', 'justteams-chest-converter', 'justteams_chest_converter'):
                excluded.append(jar.name)
                jar.unlink()
    shutil.copy2(candidate, ROOT / 'mods' / candidate.name)
    carpet = Path('F:/Pixelclub/purefabric-testserver-tab/mods/fabric-carpet-1.21-1.4.147+v240613.jar')
    shutil.copy2(carpet, ROOT / 'mods' / carpet.name)
    papi = PROJECT / 'libs/pxc-placeholders-0.1.4.jar'
    if papi.is_file():
        for jar in (ROOT / 'mods').glob('pxc-placeholders-*.jar'):
            jar.unlink()
        shutil.copy2(papi, ROOT / 'mods' / papi.name)
    config = ROOT / 'config/JustTeams'
    config.mkdir(parents=True)
    with zipfile.ZipFile(candidate) as archive:
        for name in ('config.yml', 'messages.yml', 'gui.yml', 'quests.yml', 'placeholders.yml'):
            (config / name).write_bytes(archive.read(name))
    import yaml
    settings = yaml.safe_load((config / 'config.yml').read_text(encoding='utf-8'))
    settings['storage'] = {'type': 'h2', 'table_prefix': 'donut_'}
    settings['server-identifier'] = 'fixture-local'
    settings.setdefault('team_bank', {}).update({'enabled': True, 'max_balance': 1_000_000_000})
    settings.setdefault('team_home', {}).update({'warmup_seconds': 5, 'cooldown_seconds': 5})
    settings.setdefault('team_warps', {}).update({'warmup_seconds': 5, 'cooldown_seconds': 5})
    fees = settings.setdefault('feature_costs', {}).setdefault('economy', {})
    fees.update({'bank_deposit': 0, 'bank_withdraw': 0, 'sethome': 250_000, 'setwarp': 500_000, 'rename': 50_000})
    for tier, cost in ((2, 250_000), (3, 500_000), (4, 1_000_000), (5, 2_000_000)):
        settings.setdefault('team_upgrades', {}).setdefault('tiers', {}).setdefault(tier, {})['cost'] = cost
    (config / 'config.yml').write_text(yaml.safe_dump(settings, allow_unicode=True, sort_keys=False), encoding='utf-8')
    properties = {'server-ip': '127.0.0.1', 'server-port': '25655', 'query.port': '25656',
      'online-mode': 'false', 'enforce-secure-profile': 'false', 'level-name': 'synthetic-world',
      'level-type': 'minecraft:flat', 'generate-structures': 'false', 'spawn-protection': '0',
      'view-distance': '3', 'simulation-distance': '3', 'max-players': '8', 'enable-rcon': 'false',
      'enable-query': 'false', 'motd': 'Synthetic JustTeams loopback fixture', 'allow-flight': 'true',
      'sync-chunk-writes': 'true', 'max-tick-time': '-1'}
    (ROOT / 'server.properties').write_text('\n'.join(f'{k}={v}' for k,v in properties.items())+'\n', encoding='ascii')
    (ROOT / 'eula.txt').write_text('eula=true\n', encoding='ascii')
    manifest = {'candidate': str(candidate), 'sha256': hashlib.sha256(candidate.read_bytes()).hexdigest(),
      'excluded': excluded, 'mods': sorted(p.name for p in (ROOT / 'mods').glob('*.jar')),
      'source_copied': ['mods', 'libraries', LAUNCHER], 'ports': [25655,25656], 'no_config_or_player_copy': True}
    (ROOT / 'runtime-manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(json.dumps({'rig': str(ROOT), 'mods': len(manifest['mods']), 'sha256': manifest['sha256'], 'excluded': excluded}))

def boot():
    marked()
    mailbox = ROOT / 'qa-commands.jsonl'
    mailbox.write_text('', encoding='utf-8')
    log = ROOT / f'qa-console-{time.time_ns()}.log'
    (ROOT / 'qa-current-log.txt').write_text(str(log), encoding='utf-8')
    jars = sorted((ROOT / 'mods').glob('*.jar'))
    candidate = next(jar for jar in jars if jar.name == 'JustTeams-2.5.5+pxc.1.jar')
    (ROOT / (log.stem+'.manifest.json')).write_text(json.dumps({'utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),
      'candidateSha256':hashlib.sha256(candidate.read_bytes()).hexdigest(),'mods':len(jars),
      'ports':[25655,25656],'bind':'127.0.0.1','testActors':True},indent=2),encoding='utf-8')
    with log.open('wb') as output:
        server = subprocess.Popen([str(JAVA), '-Xms1G', '-Xmx4G', '-Dpxcteams.testActors=true',
          '-Dmixin.debug.export=true', '-jar', LAUNCHER, 'nogui'], cwd=ROOT,
          stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        (ROOT / 'qa-server.pid').write_text(str(server.pid), encoding='ascii')
        print(f'own rig Java PID={server.pid} log={log}', flush=True)
        offset = 0
        while server.poll() is None:
            with mailbox.open(encoding='utf-8') as commands:
                commands.seek(offset)
                while row := commands.readline():
                    command = json.loads(row)['command']
                    server.stdin.write((command + '\n').encode())
                    server.stdin.flush()
                offset = commands.tell()
            time.sleep(0.15)
        print(f'own rig exit={server.returncode}', flush=True)

def send(command):
    marked()
    with (ROOT / 'qa-commands.jsonl').open('a', encoding='utf-8') as output:
        output.write(json.dumps({'command': command})+'\n')
    print('own-rig command queued')

def summary():
    marked()
    log = Path((ROOT / 'qa-current-log.txt').read_text(encoding='utf-8'))
    lines = log.read_text(encoding='utf-8', errors='replace').splitlines()
    keys = ('[pxc-teams]', 'JTQA', 'Done (', 'Mixin apply', 'Mixin transformation', 'Caused by:', 'Critical injection', 'InvalidInjection', 'NoSuchMethod', 'Exception in thread')
    for row in [line for line in lines if any(key in line for key in keys)][-24:]:
        print(row)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('prepare','boot','send','summary'))
    parser.add_argument('argument', nargs='?')
    args = parser.parse_args()
    if args.mode == 'prepare': prepare(Path(args.argument).resolve())
    elif args.mode == 'boot': boot()
    elif args.mode == 'send': send(args.argument)
    else: summary()
