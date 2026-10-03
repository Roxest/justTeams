"""Observe actual native ticks while root runs auth/GUI; never changes auth/state."""
import json
import hashlib
from datetime import datetime,timezone
import time
from rig import ROOT
from run_cases import qa
path=ROOT/'qa-watchdog12000.json'
first=qa('watchdog');start=first['result']['ticks'];samples=[{'utc':first['utc'],**first['result']}]
metadata={'applicationSha256':hashlib.sha256((ROOT/'mods/JustTeams-2.5.5+pxc.1.jar').read_bytes()).hexdigest(),'pid':int((ROOT/'qa-server.pid').read_text()),'log':(ROOT/'qa-current-log.txt').read_text()}
path.write_text(json.dumps({**metadata,'startTicks':start,'observedTicks':0,'samples':samples,'interval':'30 seconds','actualTicks':True},indent=2),encoding='utf-8')
while samples[-1]['ticks']-start<12000:
    time.sleep(30)
    row=qa('watchdog');sample={'utc':row['utc'],**row['result']};samples.append(sample)
    assert sample['ready'] and sample['snapshotHealthy'] and sample['provider']
    path.write_text(json.dumps({**metadata,'startTicks':start,'observedTicks':sample['ticks']-start,'samples':samples,'interval':'30 seconds','actualTicks':True},indent=2),encoding='utf-8')
print(json.dumps({'observedTicks':samples[-1]['ticks']-start,'n':len(samples),'actualTicks':True,'artifact':str(path)}))
