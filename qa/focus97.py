"""Focused state acceptance for corrected native candidate97d20c57."""
import argparse
import ast
from datetime import datetime,timezone
import json
import time
from rig import ROOT,send
from run_cases import qa,state,actor,balance,wallet,equal,click,spawn_actors

PROOF=ROOT/'qa-focus97.json'
report=json.loads(PROOF.read_text(encoding='utf-8')) if PROOF.exists() else {}

def save(key,value):
    report[key]=value;PROOF.write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps({key:value}),flush=True)

def normal():
    spawn_actors()
    for name in ('JTMoneyOwner','JTMoneyMember','JTMoneyCo'):
        qa(f'wallet {name} 10000000');send('clear '+name)
    qa('savetracereset JTMoneyOwner')
    qa('wallet JTMoneyOwner 10000000')
    trace=qa('savetrace JTMoneyOwner');assert len(trace['result'])>=2 and all(trace['result']),trace
    fence=qa('fence JTMoneyOwner 0');assert fence['result']['quiet'] and fence['result']['awaitSince']
    save('reserveBeforeOriginalSave',{'n':len(trace['result']),'allReservedAtOriginalEntry':True,'traceArtifact':trace['artifact'],'fenceArtifact':fence['artifact']})
    qa('setbank 1000000');send('tp JTMoneyOwner 30 99 0');time.sleep(.25)
    before=state('corrected-paid-home-before');row=qa('cmd JTMoneyOwner team sethome');after=row['result']
    home=after['teams'][0]['home_location'];fields=home.split(',')
    assert fields[0]=='world' and after['teams'][0]['home_server']=='fixture-local'
    coordinates=ast.literal_eval(actor(after,'JTMoneyOwner')['position'])
    assert tuple(map(float,fields[1:4]))==coordinates
    equal(balance(after),balance(before)-250000);equal(wallet(after),wallet(before));assert 'pending=0' in after['status']
    save('paidHome',{'n':1,'utc':datetime.now(timezone.utc).isoformat(),'home':home,'bank':balance(after),'wallet':wallet(after),'artifact':row['artifact']})

def home_readback():
    spawn_actors();row=qa('snapshot corrected-home-cold-readback');after=row['result'];old=report['paidHome']
    assert after['teams'][0]['home_location']==old['home'];equal(balance(after),old['bank']);equal(wallet(after),old['wallet'])
    assert 'pending=0' in after['status']
    save('paidHomeColdReadback',{'n':1,'exactHomeAndBalances':True,'artifact':row['artifact']})

def sql_fault():
    qa('close JTMoneyOwner')
    # Different inventories make an unrelated-member wipe observable.
    send('item replace entity JTMoneyOwner hotbar.0 with minecraft:diamond[minecraft:custom_data={fixture:"focus97",owner:true}] 12')
    send('item replace entity JTMoneyOwner hotbar.1 with minecraft:iron_sword[minecraft:custom_name=\'{"text":"Preserve owner inventory"}\']')
    send('item replace entity JTMoneyMember hotbar.2 with minecraft:emerald[minecraft:custom_data={fixture:"focus97",member:true}] 7')
    send('item replace entity JTMoneyCo hotbar.3 with minecraft:gold_ingot[minecraft:custom_data={fixture:"focus97",co:true}] 5');time.sleep(.3)
    qa('cmd JTMoneyOwner team ec');opened=state('sql-fault-chest-open');top=actor(opened,'JTMoneyOwner')['slots']-36
    click('JTMoneyOwner',top+27);click('JTMoneyOwner',0)
    send('item replace entity JTMoneyOwner hotbar.0 with minecraft:stone[minecraft:custom_data={fixture:"focus97",preserve:true}] 3');time.sleep(.3)
    before=state('sql-fault-before');qa('armsql')
    fault=click('JTMoneyOwner',0);after=fault['result']
    assert after['openChests']==0 and after['acted']['cursorAfter'].startswith('0 ')
    assert after['acted']['inventoryBefore']==after['acted']['inventoryAfter']
    q={row['name']:row for row in after['quarantine']};assert q['JTMoneyOwner']['actorQuarantined'] is True
    assert q['JTMoneyMember']['actorQuarantined'] is False and q['JTMoneyCo']['actorQuarantined'] is False
    for name in ('JTMoneyMember','JTMoneyCo'):
        assert actor(after,name)['inventory']==actor(before,name)['inventory']
    assert after.get('statusError')=='java.sql.SQLException'
    wal=ROOT/'config/JustTeams/chest-intents.jsonl';rows=[json.loads(line) for line in wal.read_text(encoding='utf-8').splitlines() if line]
    frozen=next(row for row in reversed(rows) if row['phase']=='FROZEN')
    assert frozen['payload']['quarantinedInventory']==after['acted']['inventoryAfter']
    cursor=json.loads(frozen['payload']['cursorEscrow']);assert cursor['slots'][0]['item']['id']=='minecraft:diamond' and cursor['slots'][0]['item']['count']==12
    save('chestSqlUnavailable',{'n':1,'leaseRevoked':True,'cursorJournalledAndCleared':True,'actorInventoryPreserved':True,
      'unrelatedInventoriesPreserved':True,'onlyActorQuarantined':True,'opid':frozen['opid'],'artifact':fault['artifact'],
      'memberInventory':actor(before,'JTMoneyMember')['inventory'],'coInventory':actor(before,'JTMoneyCo')['inventory'],'wal':str(wal)})

def login_readback():
    qa('seedprofiles')
    for name in ('JTMoneyMember','JTMoneyCo'):
        send(f'player {name} spawn at 1 100 0')
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            current=state('await-unrelated-login')
            if any(a['name']==name for a in current['actors']):break
            time.sleep(.3)
        else:raise TimeoutError('Unrelated synthetic login '+name)
    row=qa('snapshot chest-unrelated-login-readback');after=row['result'];old=report['chestSqlUnavailable']
    assert actor(after,'JTMoneyMember')['inventory']==old['memberInventory']
    assert actor(after,'JTMoneyCo')['inventory']==old['coInventory']
    q={row['name']:row for row in after['quarantine']};assert q['JTMoneyOwner']['actorQuarantined'] and not q['JTMoneyMember']['actorQuarantined'] and not q['JTMoneyCo']['actorQuarantined']
    assert after['openChests']==0 and 'pending=1' in after['status']
    send('player JTMoneyOwner spawn at 0 100 0');time.sleep(4)
    attempt=qa('snapshot chest-quarantined-owner-login')
    # Carpet intentionally ignores literal disconnect reasons; verify actual TCP separately.
    save('chestRestartLogin',{'n':1,'unrelatedInventoriesExact':True,'actorQuarantineRestored':True,'pendingOne':True,'fakeDisconnectFixtureLimit':True,'artifact':attempt['artifact']})

parser=argparse.ArgumentParser();parser.add_argument('stage',choices=('normal','home-readback','sql-fault','login-readback'));args=parser.parse_args()
{'normal':normal,'home-readback':home_readback,'sql-fault':sql_fault,'login-readback':login_readback}[args.stage]()
