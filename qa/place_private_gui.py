"""Root-authorised exact six-file donor stage, cold only; auth/wallet/DB untouched."""
import ctypes
import hashlib
import json
from pathlib import Path
import shutil
import time
import yaml
from rig import ROOT,marked
marked()
pid=int((ROOT/'qa-server.pid').read_text(encoding='ascii'));handle=ctypes.windll.kernel32.OpenProcess(0x00100000,False,pid)
if handle:ctypes.windll.kernel32.CloseHandle(handle);raise RuntimeError('Cold rig required')
stage=Path('F:/Pixelclub/scratch/justteams-cutover-20261002/private-gui-stage')
report=json.loads((stage/'sanitization-report.json').read_text(encoding='utf-8'))
assert report['owner_only_acl'] and report['behavior_unchanged'] and report['config_override_paths']==['storage','redis']
assert set(report['outputs'])=={'config.yml','gui.yml','messages.yml','commands.yml','placeholders.yml','quests.yml'},'exact authorised six files required'
config=yaml.safe_load((stage/'config.yml').read_text(encoding='utf-8'));storage=config['storage']
assert storage['type'].lower()=='h2' and storage.get('table_prefix',storage.get('prefix'))=='donut_'
assert config['redis']['enabled'] is False
for name,data in report['outputs'].items():assert hashlib.sha256((stage/name).read_bytes()).hexdigest()==data['sha256']
outside=Path('F:/Pixelclub/scratch/justteams-auth-gui-previous-20261003')/str(time.time_ns());outside.mkdir(parents=True)
for name in report['outputs']:
    target=ROOT/'config/JustTeams'/name;shutil.copy2(target,outside/name);shutil.copy2(stage/name,target)
for name,data in report['outputs'].items():assert hashlib.sha256((ROOT/'config/JustTeams'/name).read_bytes()).hexdigest()==data['sha256']
identifier=config.get('settings',{}).get('server-identifier',config.get('server-identifier','survival'))
proof={'sourceHashes':report['source'],'outputHashes':report['outputs'],'overrides':['storage','redis'],'h2Base':'config/JustTeams/data/teams-pxc','authConfigUnchanged':True,'walletSQLPlayerdataUnchanged':True,'serverIdentifier':identifier,'oldPublicFixtureOutsideRig':str(outside)}
(ROOT/'qa-private-gui-stage.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')
print(json.dumps({'exactSixHashesVerified':True,'h2LocalPrefix':'donut_','authWalletDBPreserved':True,'serverIdentifier':identifier,'artifact':str(ROOT/'qa-private-gui-stage.json')}))
