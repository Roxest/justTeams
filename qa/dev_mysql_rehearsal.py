"""DEV cold window for the justTeams MySQL rehearsal (separate catalog jt_rehearsal, never pixelclub).

Phases (each requires the previous proof):
  preflight <StartedAt> <release-id>   exact boot, player0, current jar/config hashes
  stop                                  dev_window save-all + stop (policy must already be `no`)
  stage                                 cold: app jar -> FINAL, config.yml -> host-staged dev-rehearsal-config.yml
  start                                 docker start once
  restore                               cold: config.yml -> the DEV H2 canary config (f7f8...), jar stays FINAL
No PRD path is touched; the host staging dir is /root/pixelclub/ops/justteams-cutover.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

WINDOW = Path('F:/Pixelclub/pxctools-fabric/port/runtime/dev_window.py')
spec = importlib.util.spec_from_file_location('dev_window', WINDOW); window = importlib.util.module_from_spec(spec); spec.loader.exec_module(window)
KIT = Path('F:/Pixelclub/scratch/justteams-cutover-20261002/host-rehearsal')
STAGE = '/root/pixelclub/ops/justteams-cutover'
APP = window.DEV_ROOT + '/mods/JustTeams-2.5.5+pxc.1.jar'
CONFIG = window.DEV_ROOT + '/config/JustTeams/config.yml'
FINAL = 'a35c9c0c662716bbdbcaebd2e2ecd04110d7138edc0017ae2fc63b54b7bbfe61'
H2_CONFIG = 'f7f8d7b908162d0aecf600f17ce51b4f09c2ac9a562375cf8febb99cf21e1a84'
MYSQL_CONFIG = 'f7e4ee4ce399e2770029c898f00c0233b44c0d1830682a5792c94db4867b9464'
OTHER_SIX = {'commands.yml': '2ef9edec478b0def636b1698a2058cd4f61b7ba232cc6712e3adbf068cb6a23c',
             'gui.yml': '4aed3365e6af784b9470ce6490473d30020680ca23570abfcb777e95b17786e8',
             'messages.yml': '3dcb5c3f9ed56f08be2b52803c4591c587bb768089c37a7b4fb620dfb92cb125',
             'placeholders.yml': 'e8132174ae24623f8cf4b887ca5884ed3b5fe37c291059b89e5ceee987059a84',
             'quests.yml': '5d27cdf0c40e144820b2377412560134c467ae8ffdf03976ad67127bc4b26557',
             'webhooks.yml': 'c4c216b48d87aa00259cb2be865bd98b1c8ad41ea8ab94a8d2064d79fc6194c5'}


def proof(name, value=None):
    path = KIT / f'devwin-{name}.json'
    if value is None:
        return json.loads(path.read_text(encoding='utf-8'))
    path.write_text(json.dumps(value, indent=2), encoding='utf-8'); print(json.dumps(value)[:600])


def remote(client, command):
    _, out, err = client.exec_command(command, timeout=120); data = out.read().decode().strip(); err.read()
    if out.channel.recv_exit_status() != 0:
        raise RuntimeError('remote command failed: ' + command.split()[0])
    return data


def sha(client, path):
    return remote(client, f"sha256sum '{path}'").split()[0]


def hashes(client):
    result = {'app': sha(client, APP), 'config': sha(client, CONFIG)}
    for name, expected in OTHER_SIX.items():
        result[name] = sha(client, f'{window.DEV_ROOT}/config/JustTeams/{name}')
        assert result[name] == expected, f'{name} changed'
    return result


def cold(client, started):
    state = window.inspect(client)
    assert not state['running'] and state['started_at'] == started and state['restart_policy']['Name'] == 'no' \
        and not state['restarting'] and not state['paused'], 'exact cold state with policy no required'
    return state


def replace(client, source, target, expected):
    """Server-side copy keeping the target's owner/mode; write to a temp name then rename."""
    remote(client, f"cp --preserve=mode,ownership '{target}' '{target}.jtnew' && cat '{source}' > '{target}.jtnew' && mv -f '{target}.jtnew' '{target}'")
    assert sha(client, target) == expected, 'installed hash mismatch'


def main():
    p = argparse.ArgumentParser(); p.add_argument('phase', choices=['preflight', 'stop', 'stage', 'start', 'restore']); p.add_argument('args', nargs='*'); a = p.parse_args()
    if a.phase == 'stop':
        import subprocess
        pre = proof('preflight')
        r = subprocess.run([sys.executable, str(WINDOW), 'stop', '--expected-started-at', pre['startedAt'], '--release-evidence', pre['release']], capture_output=True, text=True, encoding='utf-8')
        out = json.loads(r.stdout); proof('stop', out); assert r.returncode == 0 and out['result'] == 'COLD_EXIT_0'; return
    client = window.load_access().connect()
    try:
        if a.phase == 'preflight':
            started, release = a.args
            state = window.inspect(client); window.admit_stop(state, started)
            proof('preflight', {'startedAt': started, 'release': release, 'state': state, 'hashes': hashes(client)})
        elif a.phase == 'stage':
            pre = proof('preflight'); cold(client, pre['startedAt'])
            before = hashes(client)
            assert sha(client, f'{STAGE}/JustTeams-PXC-FINAL.jar') == FINAL and sha(client, f'{STAGE}/dev-rehearsal-config.yml') == MYSQL_CONFIG
            replace(client, f'{STAGE}/JustTeams-PXC-FINAL.jar', APP, FINAL)
            replace(client, f'{STAGE}/dev-rehearsal-config.yml', CONFIG, MYSQL_CONFIG)
            cold(client, pre['startedAt'])
            proof('stage', {'before': before, 'after': hashes(client)})
        elif a.phase == 'start':
            pre = proof('preflight'); cold(client, pre['startedAt'])
            remote(client, 'docker start ' + window.DEV_CONTAINER)
            proof('start', window.inspect(client))
        else:
            started = a.args[0]; cold(client, started)
            h2 = KIT / 'dev-h2-config.yml'
            assert hashlib.sha256(h2.read_bytes()).hexdigest() == H2_CONFIG, 'local H2 config copy mismatch'
            with client.open_sftp() as sftp:
                sftp.put(str(h2), f'{STAGE}/dev-h2-config.yml')
            replace(client, f'{STAGE}/dev-h2-config.yml', CONFIG, H2_CONFIG)
            cold(client, started)
            proof('restore', hashes(client))
    finally:
        client.close()


if __name__ == '__main__':
    main()
