"""Cold own-rig replacement of exactly the authorised AC artifact; no keys/config copied."""
import ctypes
import hashlib
import json
from pathlib import Path
import shutil
import time
import zipfile
from rig import ROOT,marked
marked();pid=int((ROOT/'qa-server.pid').read_text());handle=ctypes.windll.kernel32.OpenProcess(0x00100000,False,pid)
if handle:ctypes.windll.kernel32.CloseHandle(handle);raise RuntimeError('cold rig required')
source=Path('F:/Pixelclub/scratch/justteams-cutover-20261002/artifacts/PxcAC-Server-1.18.9-current-dev-64516462.jar');sha='64516462774cd1c860176c01e4668f3ec7e8d96f4f75052df883e6ff6108f2fb';assert hashlib.sha256(source.read_bytes()).hexdigest()==sha
with zipfile.ZipFile(source) as archive:source_meta=json.loads(archive.read('fabric.mod.json'))
assert source_meta['id']=='pxcac-server' and source_meta['version']=='1.18.9'
outside=Path('F:/Pixelclub/scratch/justteams-auth-ac-20261003')/str(time.time_ns());outside.mkdir(parents=True)
old=[];old_paths=[]
for jar in (ROOT/'mods').glob('*PxcAC*.jar'):
    with zipfile.ZipFile(jar) as archive:
        try:meta=json.loads(archive.read('fabric.mod.json'))
        except KeyError:continue
    if meta.get('id')=='pxcac-server':old.append({'jar':jar.name,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest()});old_paths.append(jar)
assert len(old)==1,'exactly one old own AC jar expected'
for jar in old_paths:jar.rename(outside/jar.name)
target=ROOT/'mods/PxcAC-Server-1.18.9.jar';shutil.copy2(source,target)
proof={'source':str(source),'sha256':sha,'old':old,'archiveOutsideServerRoot':str(outside),'ownACConfigSha256':hashlib.sha256((ROOT/'config/pxcac/config.json').read_bytes()).hexdigest(),'appPrivateSixAuthH2FixtureACConfigUnchanged':True,'noKeysLicencesConfigCopiesOrBypass':True,'devLoadedVersion':'UNKNOWN; no log proof','ownLoadedVersion':'pending boot'}
(ROOT/'qa-ac-1.18.9-stage.json').write_text(json.dumps(proof,indent=2),encoding='utf-8');print('Only authorised PxcAC1.18.9 staged cold; old jar outside rig; keys/config unchanged')
