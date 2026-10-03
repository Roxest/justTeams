"""Timed PC-fixture rehearsal of the real cold lane and rollback lane (ColdChests --fixture-root + Arclight converter rig).

Fresh marked H2 fixture each run; never Docker, server, MySQL or player files. Writes rehearsal-report.json in --work.
Converter rig must already exist (tools/chest_rig.py create); prior conversion outputs are moved aside, never deleted.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time

JAVA = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin/java.exe'
PY = 'F:/Pixelclub/tools/.venv/Scripts/python.exe'
HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--app', required=True); p.add_argument('--driver', required=True); p.add_argument('--gson', required=True)
    p.add_argument('--converter', required=True); p.add_argument('--rig', required=True); p.add_argument('--donor', required=True)
    p.add_argument('--fixture', required=True); p.add_argument('--work', required=True)
    a = p.parse_args()
    work, fixture, rig = Path(a.work), Path(a.fixture), Path(a.rig)
    assert not work.exists() and not fixture.exists(), 'fresh work/fixture required'
    work.mkdir(parents=True)
    cp = ';'.join([a.app, a.driver, a.gson])
    steps, report = [], {'pins': {'app': sha(a.app), 'driver': sha(a.driver), 'converter': sha(a.converter), 'donorExport': sha(a.donor)}}

    def timed(name, fn):
        t = time.monotonic(); out = fn(); steps.append({'step': name, 'seconds': round(time.monotonic() - t, 3)}); return out

    def java(*args):
        r = subprocess.run([JAVA, '-cp', cp, *args], capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(f'{args[0]} {args[1] if len(args) > 1 else ""} exit {r.returncode}: {r.stderr.strip()[:300]}')
        return r.stdout.strip()

    def harness(*args):
        return java(str(HERE / 'Rehearsal.java'), *args)

    def cli(*args):
        return json.loads(java('com.pixelclub.teams.tools.ColdChests', *args, '--properties', str(work / 'rehearsal.properties'), '--fixture-root', str(fixture)))

    def convert(command, name_in, src, name_out):
        conv = rig / 'chest-conversion'
        aside = conv / f'aside-{time.time_ns()}'; aside.mkdir()
        for f in conv.glob('*.json'):
            shutil.move(str(f), aside / f.name)
        shutil.copy2(src, conv / name_in)
        target = next(rig.glob('mods/justteams-chest-converter-*.jar'))
        assert sha(target) == sha(a.converter), 'rig converter pin mismatch'
        r = subprocess.run([PY, str(PROJECT / 'tools/chest_rig.py'), 'launch', '--root', str(rig), '--command', command, '--timeout', '900'], capture_output=True, text=True)
        result = json.loads(r.stdout.strip().splitlines()[-1])
        assert result['conversion_pass'], result
        shutil.copy2(conv / name_out, work / name_out)
        return work / name_out

    url = 'jdbc\\:h2\\:file\\:' + str(fixture / 'teams').replace('\\', '/').replace(':', '\\:') + ';MODE\\=MySQL;DB_CLOSE_ON_EXIT\\=FALSE;IFEXISTS\\=TRUE'
    (work / 'rehearsal.properties').write_text(f'jdbc_url={url}\nusername=sa\npassword=\ndriver=eu.kotori.justTeams.libs.h2.Driver\nprefix=donut_\n', encoding='ascii')

    timed('seed-fixture', lambda: harness('seed', str(fixture), a.donor, str(work / 'donor-cold.json')))
    timed('manifest-before', lambda: harness('manifest', str(fixture), str(work / 'm0.json')))
    forward = timed('forward-convert(rig boot+convert+stop)', lambda: convert('jtchestconvert forward', 'donor-input.json', work / 'donor-cold.json', 'native-output.json'))
    report['import'] = timed('import --apply', lambda: cli('import', '--snapshot', str(work / 'donor-cold.json'), '--artifact', str(forward), '--output', str(work / 'import-proof.json'), '--apply'))
    timed('readback-after-import', lambda: (harness('manifest', str(fixture), str(work / 'm1.json')), harness('natives', str(fixture), str(work / 'n1.json'))))
    report['replay'] = timed('import replay (exact gen1)', lambda: cli('import', '--snapshot', str(work / 'donor-cold.json'), '--artifact', str(forward), '--output', str(work / 'import-replay-proof.json'), '--apply'))
    timed('post-cutover legit changes', lambda: harness('mutate', str(fixture), str(work / 'mutation.json')))
    timed('manifest-post-cutover', lambda: harness('manifest', str(fixture), str(work / 'm2.json')))
    report['export'] = timed('export-native', lambda: cli('export-native', '--output', str(work / 'native-export.json')))
    reverse = timed('reverse-convert(rig boot+convert+stop)', lambda: convert('jtchestconvert reverse', 'native-input.json', work / 'native-export.json', 'reverse-output.json'))
    report['rollback'] = timed('rollback --apply', lambda: cli('rollback', '--snapshot', str(work / 'native-export.json'), '--artifact', str(reverse), '--output', str(work / 'rollback-proof.json'), '--apply'))
    timed('readback-after-rollback', lambda: harness('manifest', str(fixture), str(work / 'm3.json')))
    again = subprocess.run([JAVA, '-cp', cp, 'com.pixelclub.teams.tools.ColdChests', 'rollback', '--snapshot', str(work / 'native-export.json'), '--artifact', str(reverse),
                            '--output', str(work / 'rollback-again.json'), '--apply', '--properties', str(work / 'rehearsal.properties'), '--fixture-root', str(fixture)], capture_output=True, text=True)
    # Only the CLI's own refusal counts; a classpath/lock failure is not evidence of replay refusal.
    report['rollbackReplayRefused'] = again.returncode == 1 and again.stderr.strip() == 'COLD_CHESTS_REFUSED' and again.stdout.strip() == ''

    m = {n: json.loads((work / f'{n}.json').read_text()) for n in ('m0', 'm1', 'm2', 'm3')}
    n1 = json.loads((work / 'n1.json').read_text()); fw = json.loads(forward.read_text(encoding='utf-8'))
    rv = {r['team_id']: r for r in json.loads(reverse.read_text(encoding='utf-8'))['rows']}
    mut = json.loads((work / 'mutation.json').read_text())
    donor = [k for k in m['m0'] if k.startswith('donut_')]
    ch, nw = str(mut['chest_changed_team']), str(mut['chest_new_team'])
    checks = {
        'import_22_donor_tables_unchanged': all(m['m0'][k] == m['m1'][k] for k in donor),
        'import_bank_sum_unchanged': m['m0']['bank_sum'] == m['m1']['bank_sum'],
        'import_native_rows_exact_vs_artifact': sum(1 for r in fw['rows'] if n1[str(r['team_id'])]['inventory_sha256'] == hashlib.sha256(r['inventory_json'].encode()).hexdigest() and n1[str(r['team_id'])]['original_sha256'] == r['original_sha256'] and n1[str(r['team_id'])]['generation'] == 1),
        'import_rows': len(fw['rows']),
        'rollback_money_equals_post_cutover': m['m3']['donut_teams'] == m['m2']['donut_teams'] and m['m3']['bank_sum'] == m['m2']['bank_sum'],
        'rollback_money_not_reset_to_pre_cutover': m['m3']['bank_sum'] != m['m0']['bank_sum'],
        'rollback_members_unchanged': m['m3']['donut_team_members'] == m['m0']['donut_team_members'],
        'rollback_other_20_tables_unchanged': all(m['m3'][k] == m['m0'][k] for k in donor if k not in ('donut_teams', 'donut_team_enderchest')),
        'rollback_untouched_chests_exact_original_bytes': sum(1 for t in m['m0']['donor_chest_sha'] if t != ch and m['m3']['donor_chest_sha'][t] == m['m0']['donor_chest_sha'][t]),
        'rollback_untouched_chests_total': len(m['m0']['donor_chest_sha']) - 1,
        'rollback_changed_chest_gets_reverse_blob': m['m3']['donor_chest_sha'][ch] == rv[int(ch)]['reverse_sha256'],
        'rollback_new_chest_inserted': m['m3']['donor_chest_sha'].get(nw) == rv[int(nw)]['reverse_sha256'],
        'rollback_addon_chest_rows': m['m3']['pxc_teams_chests']['rows'],
    }
    report.update({'steps': steps, 'checks': checks, 'mutation': mut})
    (work / 'rehearsal-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'steps': steps, 'checks': checks, 'rollbackReplayRefused': report['rollbackReplayRefused']}, indent=1))
    expected_rows = checks['import_rows']
    failed = [k for k, v in checks.items() if v is False] + [k for k in ('import_native_rows_exact_vs_artifact',) if checks[k] != expected_rows]
    if checks['rollback_untouched_chests_exact_original_bytes'] != checks['rollback_untouched_chests_total'] or checks['rollback_addon_chest_rows'] != 0:
        failed.append('rollback_chest_bytes_or_addon_rows')
    if not report['rollbackReplayRefused']:
        failed.append('rollbackReplayRefused')
    if failed:
        raise SystemExit('REHEARSAL FAILED: ' + ', '.join(failed))


if __name__ == '__main__':
    main()
