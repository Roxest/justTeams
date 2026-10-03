"""Exact cold readback of the synthetic chest; proof contains no auth credentials."""
import ctypes
import json
from pathlib import Path
import subprocess
import tempfile
import argparse
from rig import ROOT,JAVA,marked
parser=argparse.ArgumentParser();parser.add_argument('proof');args=parser.parse_args();marked()
pid=int((ROOT/'qa-server.pid').read_text());handle=ctypes.windll.kernel32.OpenProcess(0x00100000,False,pid)
if handle:ctypes.windll.kernel32.CloseHandle(handle);raise RuntimeError('cold fixture required')
proof=json.loads(Path(args.proof).read_text());chest=proof['chestGrowth']['coldExpected']
with tempfile.TemporaryDirectory(prefix='jtqa-cold-chest-') as temp:
    expected=Path(temp)/'inventory.json';expected.write_text(chest['inventoryJson'],encoding='utf-8')
    subprocess.run([str(JAVA),'-cp',str(ROOT/'mods/JustTeams-2.5.5+pxc.1.jar'),'qa/ColdChestReadback.java',str(ROOT),str(expected),str(chest['generation']),chest.get('originalSha256') or 'null'],check=True)
result={'applicationSha256':proof['applicationSha256'],'rung':'state','exactColdInventory':True,'size':json.loads(chest['inventoryJson'])['size'],'generation':chest['generation'],'sourceSha256':chest.get('originalSha256'),'pending':0,'proof':args.proof}
(ROOT/'qa-chest-growth-cold.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))
