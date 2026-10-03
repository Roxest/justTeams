"""Only known authorised local bundle jars, with replaced jars outside the cold rig."""
import hashlib
import ctypes
import json
from pathlib import Path
import shutil
import time
from rig import ROOT,marked
marked()
pid=int((ROOT/'qa-server.pid').read_text(encoding='ascii'));handle=ctypes.windll.kernel32.OpenProcess(0x00100000,False,pid)
if handle:ctypes.windll.kernel32.CloseHandle(handle);raise RuntimeError('cold rig required before jar replacement')
sources=[(Path('F:/Pixelclub/scratch/papi-night-20261002/pack-review/pixelclubpack-fabric-1.8.12.jar'),'e7f7c7b5bf611f49b40c5ac2404737a2b2298fc344a1968f873e1ca0ecfd1967','pixelclubpack-fabric-*.jar'),(Path('F:/Pixelclub/scratch/papi-night-20261002/release/pxc-placeholders-0.1.5.jar'),'e085c5a7295cfb21df30bd24d631e901afb9294c2825d310be7a5681efd4dfec','pxc-placeholders-*.jar')]
outside=Path('F:/Pixelclub/scratch/justteams-auth-bundle-20261003')/str(time.time_ns());outside.mkdir(parents=True)
for source,sha,pattern in sources:
    assert hashlib.sha256(source.read_bytes()).hexdigest()==sha
    for old in (ROOT/'mods').glob(pattern):old.rename(outside/old.name)
    shutil.copy2(source,ROOT/'mods'/source.name)
(ROOT/'qa-auth-bundle.json').write_text(json.dumps({'packSha256':sources[0][1],'papiSha256':sources[1][1],'lpVersion':'5.4.140','replacedJarsOutsideRig':str(outside),'abi':'Pack getInstance/getEconomy/hasAccount/balance/deposit/withdraw/Result.success and Pxc register/parse retained'},indent=2),encoding='utf-8')
print('Known Pack1.8.12/Pxc0.1.5 staged; LP5.4.140 retained; no configs copied')
