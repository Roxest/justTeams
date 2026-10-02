"""Archive only the own synthetic H2/WAL outside the cold rig before re-seeding."""
import ctypes
import argparse
import json
from pathlib import Path
import time
from rig import ROOT, marked

parser=argparse.ArgumentParser();parser.add_argument('--accounts',action='store_true');args=parser.parse_args()
marked()
pid=int((ROOT/'qa-server.pid').read_text(encoding='ascii'))
process=ctypes.windll.kernel32.OpenProcess(0x00100000,False,pid)
if process:
    ctypes.windll.kernel32.CloseHandle(process)
    raise RuntimeError('Refuse reset while previous own Java PID exists')
archive=Path('F:/Pixelclub/scratch/justteams-runtime-seeds-20261003')/str(time.time_ns())
archive.mkdir(parents=True)
targets=list((ROOT/'config/JustTeams/data').glob('teams-pxc.*'))
for name in ('bank-intents.jsonl','chest-intents.jsonl'):
    wal=ROOT/'config/JustTeams'/name
    if wal.exists():targets.append(wal)
for path in targets:
    assert path.resolve().is_relative_to(ROOT.resolve()) and path.is_file() and not path.is_symlink()
    path.rename(archive/path.name)
if args.accounts:
    users=ROOT/'config/impactor/economy/accounts/users'
    expected={'a5eb7d24-72b6-3d8f-87f4-b88e663f22eb','b34e1499-dd5c-37f3-b77b-1f6ff6bd6516','55a79953-698e-30c3-8d4f-9a6911e26524','4397ad4b-1686-3bac-866f-5f2effde0bb3','0b1c63bb-b518-3dc0-a796-0a1bb5f704bb','1ac18a09-8a22-3a52-b131-98d4689ce192'}
    accounts=list(users.glob('*/*.conf'))
    assert all(path.stem in expected and path.is_file() and not path.is_symlink() for path in accounts), 'Refuse unknown account identity'
    for path in accounts:
        destination=archive/'synthetic-accounts'/path.relative_to(users);destination.parent.mkdir(parents=True,exist_ok=True);path.rename(destination)
(archive/'seed-reset.json').write_text(json.dumps({'utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),
  'sourceRoot':str(ROOT),'files':[path.name for path in targets],'syntheticOnly':True},indent=2),encoding='utf-8')
print(json.dumps({'archiveOutsideRig':str(archive),'files':[path.name for path in targets]}))
