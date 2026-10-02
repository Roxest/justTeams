"""Final immutable creative/claim/preflight regressions; no production paths."""
import json
import time
from rig import ROOT,send
from run_cases import qa,state,actor,wallet,balance,equal,click,spawn_actors
from matrix_b613 import patch

PROOF=ROOT/'qa-final-latest.json'
report=json.loads(PROOF.read_text(encoding='utf-8')) if PROOF.exists() else {}
def save(key,value):report[key]=value;PROOF.write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps({key:{k:v for k,v in value.items() if k!='fullInventory'}}),flush=True)
def first():
    spawn_actors();s=state('latest-preserved-escrow');old=json.loads((ROOT/'qa-escrow-partial-b613.json').read_text(encoding='utf-8'));assert actor(s,'JTMoneyOwner')['inventory']==old['inventory'] and 'phase=ESCROW' in s['pending']
    patch('config.yml','features.team_enderchest',False)
    send('item replace entity JTMoneyOwner hotbar.1 with minecraft:air');time.sleep(.2)
    qa('core claim JTMoneyOwner');after=state('latest-featureoff-claim');assert 'pending=0' in after['status']
    assert 'count:6,id:"minecraft:diamond"' in actor(after,'JTMoneyOwner')['inventory']
    before=actor(after,'JTMoneyOwner')['inventory'];qa('core claim JTMoneyOwner');assert actor(state('latest-claim-repeat'),'JTMoneyOwner')['inventory']==before
    patch('config.yml','features.team_enderchest',True)
    codec=qa('codecfloat');assert codec['result']['exactCanonicalRoundtrip']
    save('priorEscrowRecipientFeatureOffClaim',{'remaining6ReturnedOnce':True,'noDupRepeat':True,'n':1})
    save('actualScientificItemCodec',{'tinyFloat':1e-5,'largeFloat':1e20,'tinyDouble':1e-30,'exactRoundtrip':True,'artifact':codec['artifact']})
def negative():
    initial=state('preflight-initial')
    if any(m['player_uuid']==actor(initial,'JTMoneyMember')['uuid'] for m in initial['team_members']):qa('core kick JTMoneyOwner JTMoneyMember')
    patch('config.yml','feature_costs.economy.team_creation',50)
    before=state('preflight-before')
    current=before['teams'][0]
    for command in ['team create '+current['name']+' NEW','team create FreshDupTag '+current['tag'],'team create RawTagQA '+('<red>'*51)+'TAG']:
        qa('cmd JTMoneyMember '+command);after=state('preflight-after');equal(wallet(after,'JTMoneyMember'),wallet(before,'JTMoneyMember'));equal(balance(after),balance(before));assert 'pending=0' in after['status'] and len(after['teams'])==1
    for command in ['team setwarp '+('x'*33),'team setwarp valid '+('p'*65)]:
        qa('cmd JTMoneyOwner '+command);after=state('warp-preflight');equal(wallet(after),wallet(before));equal(balance(after),balance(before));assert 'pending=0' in after['status']
    patch('config.yml','feature_costs.economy.enabled',False);patch('config.yml','settings.rename_cooldown',0)
    target='RenameFree2' if current['name']=='RenameFreeQA' else 'RenameFreeQA'
    qa('cmd JTMoneyOwner team rename '+target);after=state('rename-economyoff');assert after['teams'][0]['name']==target;equal(balance(after),balance(before));equal(wallet(after),wallet(before))
    patch('config.yml','feature_costs.economy.enabled',True)
    save('fundedInvalidPreflight',{'duplicateNameTagRawTag255':True,'warpName33Password65':True,'noDebitOrFreeze':True,'economyOffRenameFree':True,'n':6})
def close(mode):
    qa('close JTMoneyOwner');send('gamemode '+mode+' JTMoneyOwner');send('clear JTMoneyOwner');send('item replace entity JTMoneyOwner hotbar.0 with minecraft:diamond 7');time.sleep(.3)
    qa('cmd JTMoneyOwner team ec');s=state('creative-open');assert actor(s,'JTMoneyOwner')['menu'].endswith('TeamChests$Menu');top=actor(s,'JTMoneyOwner')['slots']-36
    click('JTMoneyOwner',top+27);click('JTMoneyOwner',0);qa('fill JTMoneyOwner');click('JTMoneyOwner',0);row=qa('close JTMoneyOwner');s=row['result']
    assert 'phase=ESCROW' in s['pending'] and 'pending=1' in s['status'] and actor(s,'JTMoneyOwner')['cursor'].startswith('0 ')
    save(mode+'FullClose',{'escrow7':True,'fullInventory':actor(s,'JTMoneyOwner')['inventory'],'artifact':row['artifact'],'n':1})
def partial(mode):
    spawn_actors();send('gamemode '+mode+' JTMoneyOwner');time.sleep(.2);s=state(mode+'-cold-escrow');assert actor(s,'JTMoneyOwner')['inventory']==report[mode+'FullClose']['fullInventory'] and 'phase=ESCROW' in s['pending']
    send('item replace entity JTMoneyOwner hotbar.0 with minecraft:diamond 63');time.sleep(.2);qa('core claim JTMoneyOwner');s=state(mode+'-partial');assert 'phase=ESCROW' in s['pending']
    assert 'count:64,id:"minecraft:diamond"' in actor(s,'JTMoneyOwner')['inventory'];before=actor(s,'JTMoneyOwner')['inventory'];qa('core claim JTMoneyOwner');assert actor(state(mode+'-repeat'),'JTMoneyOwner')['inventory']==before
    send('item replace entity JTMoneyOwner hotbar.1 with minecraft:air');time.sleep(.2);qa('core claim JTMoneyOwner');s=state(mode+'-completed');assert 'pending=0' in s['status'] and 'count:6,id:"minecraft:diamond"' in actor(s,'JTMoneyOwner')['inventory']
    before=actor(s,'JTMoneyOwner')['inventory'];qa('core claim JTMoneyOwner');assert actor(state(mode+'-completed-repeat'),'JTMoneyOwner')['inventory']==before
    save(mode+'ColdPartialClaim',{'coldExact':True,'oneThenSixReturned':True,'partialAndCompleteRepeatNoDup':True,'n':1})

import argparse
parser=argparse.ArgumentParser();parser.add_argument('stage',choices=('first','negative','creative-close','creative-partial','survival-close','survival-partial'));args=parser.parse_args()
if args.stage=='first':first()
elif args.stage=='negative':negative()
elif args.stage.endswith('-close'):close(args.stage.split('-')[0])
else:partial(args.stage.split('-')[0])
