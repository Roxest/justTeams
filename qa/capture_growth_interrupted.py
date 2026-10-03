"""Retain AC-interrupted real custody attempt, without claiming its explicit close passed."""
import hashlib
import json
from rig import ROOT
from run_cases import qa,actor,balance,wallet,equal
rows=[(p,json.loads(p.read_text())) for p in sorted((ROOT/'qa-evidence').glob('1790991190119-*.json'))]
base=next(r['result'] for _,r in rows if r['action']=='snapshot chest-resume-before')
placed=next(r['result'] for _,r in rows if r['action']=='click JTMoneyOwner 33 PICKUP 0')
latest=qa('snapshot chest-growth-disconnect-close')['result'];assert latest['chest']==placed['chest'] and latest['openChests']==0 and 'pending=0' in latest['status'];equal(balance(placed),balance(base));equal(wallet(placed),wallet(base));assert actor(placed,'JTMoneyOwner')['cursor'].startswith('0 ')
before=json.loads(base['chest']['inventoryJson']);after=json.loads(placed['chest']['inventoryJson']);assert after['slots'][33]==before['slots'][0] and after['size']==36 and placed['chest']['generation']==19
proof={'applicationSha256':hashlib.sha256((ROOT/'mods/JustTeams-2.5.5+pxc.1.jar').read_bytes()).hexdigest(),'rung':'state','explicitCloseBatchPassed':False,'interruption':'genuine vanilla client lacked PxcAC handshake; no bypass attempted','automaticDisconnectCloseObserved':True,'chestGrowth':{'tier':2,'actualSlots':36,'persistedSize':36,'stockSlot':33,'componentsCountExact':True,'sourceSha256':None,'generation':19,'cursorEmpty':True,'moneyUnchanged':True,'coldExpected':placed['chest']},'evidence':[str(p) for p,_ in rows]}
(ROOT/'qa-chest-growth-ac-interrupted.json').write_text(json.dumps(proof,indent=2),encoding='utf-8');print('AC-interrupted custody attempt preserved; explicit close not claimed; native disconnect close lease0/pending0 verified')
