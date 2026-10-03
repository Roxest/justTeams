"""Retain only narrow join/disconnect attribution; never reads eyes credentials."""
import hashlib
import json
from pathlib import Path
from rig import ROOT
log=Path((ROOT/'qa-current-log.txt').read_text().strip())
events=[]
for line in log.read_text(encoding='utf-8',errors='replace').splitlines():
    if 'PxcEyes2' not in line:continue
    if 'Player PxcEyes2 joined. Sending handshake challenge' in line:events.append({'timeICT':line[1:9],'event':'join challenge'})
    if 'PxcEyes2 lost connection: Kicked: Cryptographic handshake verification failed.' in line:events.append({'timeICT':line[1:9],'event':'cryptographic handshake verification failed'})
jar=ROOT/'mods/PxcAC-Server-1.18.8.jar'
joins=sum(e['event']=='join challenge' for e in events);rejects=len(events)-joins;assert joins>0 and joins==rejects,'this artifact requires every observed join to be cryptographically rejected'
proof={'rung':'executed','identity':'PxcEyes2','serverACVersion':'1.18.8','serverACSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'events':events,'authenticatedNativeSessionProven':False,'unauthenticatedEngineLifecycleNotMeasured':True,'joinGateRule':'4 short connections in300s ->180s cooldown','noAuthOrACBypass':True,'log':str(log)}
(ROOT/'qa-eyes-auth-blocker.json').write_text(json.dumps(proof,indent=2),encoding='utf-8');print(json.dumps({'events':len(events),'authenticatedNativeSessionProven':False,'artifact':str(ROOT/'qa-eyes-auth-blocker.json')}))
