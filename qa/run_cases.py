"""Sequential console dispatch and independent state assertions for the marked own rig."""
import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import time
import uuid
from rig import ROOT, marked, send

EVIDENCE=ROOT / 'qa-evidence'
REPORT=json.loads((ROOT/'qa-report.json').read_text(encoding='utf-8')) if (ROOT/'qa-report.json').exists() else []

def qa(command):
    marked()
    previous=set(EVIDENCE.glob('*.json')) if EVIDENCE.exists() else set()
    send('jtqa '+command)
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        fresh=set(EVIDENCE.glob('*.json'))-previous if EVIDENCE.exists() else set()
        if fresh:
            path=sorted(fresh)[-1]
            try: row=json.loads(path.read_text(encoding='utf-8'))
            except json.JSONDecodeError:
                time.sleep(.05)
                continue
            row['artifact']=str(path)
            if 'error' in row:
                raise RuntimeError(f'{command}: {row["error"]}: {row.get("message", "")} [{path}]')
            if command.startswith('cmd '):
                # Vanilla nested dispatch queues the real command until this QA handler returns.
                # A subsequent console command forms a server-thread state readback barrier.
                settled=qa('snapshot after-dispatch')
                settled['dispatchArtifact']=row['artifact']
                return settled
            return row
        time.sleep(.1)
    raise TimeoutError('No QA evidence for '+command)

def state(label):
    return qa('snapshot '+label)['result']

def actor(snapshot,name):
    return next(a for a in snapshot['actors'] if a['name']==name)

def balance(snapshot):
    return float(snapshot['teams'][0]['balance'])

def wallet(snapshot,name='JTMoneyOwner'):
    return float(actor(snapshot,name)['wallet'])

def equal(actual,expected):
    assert math.isclose(actual,expected,rel_tol=0,abs_tol=1e-7), (actual,expected)

def check(name,fn):
    began=datetime.now(timezone.utc).isoformat()
    try:
        artifacts=fn()
        result={'case':name,'rung':'state','n':1,'interval':'per operation','utcStart':began,
          'utcEnd':datetime.now(timezone.utc).isoformat(),'pass':True,'artifacts':artifacts}
    except Exception as error:
        result={'case':name,'rung':'state','n':1,'interval':'per operation','utcStart':began,
          'utcEnd':datetime.now(timezone.utc).isoformat(),'pass':False,'error':str(error)}
    REPORT.append(result)
    (ROOT / 'qa-report.json').write_text(json.dumps(REPORT,indent=2),encoding='utf-8')
    print(json.dumps(result),flush=True)
    return result['pass']

def spawn_actors():
    qa('seedprofiles')
    for index,name in enumerate(('JTMoneyOwner','JTMoneyMember','JTMoneyCo')):
        send(f'player {name} spawn at {index*3} 100 0')
        deadline=time.monotonic()+12
        while time.monotonic()<deadline:
            snapshot=state('spawn-'+name)
            if any(a['name']==name for a in snapshot['actors']):
                break
            time.sleep(.5)
        else: raise TimeoutError('Synthetic actor spawn '+name)
        send(f'gamemode creative {name}')
    send('fill -5 98 -5 35 98 5 minecraft:stone')
    time.sleep(.3)
    return []

def initialise():
    spawn_actors()
    status=qa('status');assert 'ready=true' in status['result'] and 'provider=true' in status['result'],status
    for name in ('JTMoneyOwner','JTMoneyMember','JTMoneyCo'):
        qa(f'wallet {name} 10000000')
    snapshot=state('initial')
    assert len(snapshot['actors'])==3
    for name in ('JTMoneyOwner','JTMoneyMember','JTMoneyCo'):
        assert actor(snapshot,name)['authenticated'] is True
    equal(balance(snapshot),123.005)
    return [status['artifact']]

def transfer_case(name,verb,raw,amount):
    before=state(name+'-before')
    row=qa(f'cmd JTMoneyOwner team bank {verb} {raw}')
    after=row['result']
    delta=amount if verb=='deposit' else -amount
    equal(balance(after),balance(before)+delta)
    equal(wallet(after),wallet(before)-delta)
    equal(wallet(after)+balance(after),wallet(before)+balance(before))
    assert 'pending=0' in after['status']
    return [row['artifact']]

def refused_case(name,command,who='JTMoneyOwner'):
    before=state(name+'-before');row=qa(f'cmd {who} '+command);after=row['result']
    equal(balance(after),balance(before));equal(wallet(after,who),wallet(before,who))
    assert 'pending=0' in after['status']
    return [row['artifact']]

def replay():
    before=state('replay-before')
    op='jtqa_replay_'+uuid.uuid4().hex[:12]
    first=qa(f'bank JTMoneyOwner deposit 1.25 {op}')
    second=qa(f'bank JTMoneyOwner deposit 1.25 {op}')
    equal(balance(first['result']),balance(before)+1.25)
    equal(balance(second['result']),balance(first['result']))
    equal(wallet(second['result']),wallet(first['result']))
    return [first['artifact'],second['artifact'],qa('op '+op)['artifact']]

def cap():
    before=state('cap-control-before');qa('setbank 999999999.995')
    artifacts=refused_case('cap','team bank deposit 0.01')
    qa('setbank '+str(balance(before)))
    return artifacts

def click(name,slot,kind='PICKUP',button=0):
    time.sleep(.3)
    return qa(f'click {name} {slot} {kind} {button}')

def gui_bank():
    before=state('gui-bank-before');qa('cmd JTMoneyOwner team bank')
    click('JTMoneyOwner',11)
    row=qa('chat JTMoneyOwner 0.01');after=row['result']['state']
    equal(balance(after),balance(before)+.01);equal(wallet(after),wallet(before)-.01)
    repeated=qa('chat JTMoneyOwner 0.01')['result']['state']
    equal(balance(repeated),balance(after));equal(wallet(repeated),wallet(after))
    return [row['artifact']]

def gui_settings():
    qa('cmd JTMoneyOwner team settings');before=state('gui-settings-before');toggle=click('JTMoneyOwner',15)
    assert toggle['result']['teams'][0]['is_public'] != before['teams'][0]['is_public']
    click('JTMoneyOwner',11);row=qa('chat JTMoneyOwner JTX')
    assert row['result']['state']['teams'][0]['tag']=='JTX'
    return [toggle['artifact'],row['artifact']]

def gui_transfer():
    before=state('transfer-before')
    qa('cmd JTMoneyOwner team transfer JTMoneyCo');confirmed=click('JTMoneyOwner',11)
    after=confirmed['result'];assert after['teams'][0]['owner_uuid']==actor(after,'JTMoneyCo')['uuid']
    old=next(m for m in after['team_members'] if m['player_uuid']==actor(after,'JTMoneyOwner')['uuid'])
    new=next(m for m in after['team_members'] if m['player_uuid']==actor(after,'JTMoneyCo')['uuid'])
    assert old['role']=='MEMBER' and old['can_withdraw'] is False and new['role']=='OWNER' and new['can_withdraw'] is True
    qa('cmd JTMoneyCo team transfer JTMoneyOwner');back=click('JTMoneyCo',11)
    assert back['result']['teams'][0]['owner_uuid']==before['teams'][0]['owner_uuid']
    return [confirmed['artifact'],back['artifact']]

def kick_invite():
    qa('cmd JTMoneyOwner team kick JTMoneyMember');kicked=state('kick')
    assert not any(m['player_uuid']==actor(kicked,'JTMoneyMember')['uuid'] for m in kicked['team_members'])
    qa('cmd JTMoneyOwner team invite JTMoneyMember');invited=state('invited')
    assert len(invited['team_invites'])==1
    qa('cmd JTMoneyMember team invites');accepted=click('JTMoneyMember',10)
    after=accepted['result'];assert len(after['team_members'])==3 and not after['team_invites']
    return [accepted['artifact']]

def home_cancel():
    qa('setbank 1000000')
    send('tp JTMoneyOwner 30 99 0');time.sleep(.3)
    sethome=qa('cmd JTMoneyOwner team sethome');home=sethome['result']['teams'][0]['home_location']
    assert home and '30.0' in home
    send('tp JTMoneyOwner 0 99 0');time.sleep(.3)
    qa('cmd JTMoneyOwner team home');time.sleep(.5)
    send('tp JTMoneyOwner 2 99 0');time.sleep(5.2)
    after=state('home-move-cancel');assert actor(after,'JTMoneyOwner')['position'].startswith('(2.0,')
    qa('cmd JTMoneyOwner team home');time.sleep(5.2)
    completed=state('home-completed');assert actor(completed,'JTMoneyOwner')['position'].startswith('(30.0,')
    return [sethome['artifact']]

def pvp():
    send('tp JTMoneyMember 1 99 0');send('gamemode survival JTMoneyMember');time.sleep(.3)
    disabled=qa('pvp');assert disabled['result']['deny'] is True
    blocked=qa('hit JTMoneyOwner JTMoneyMember 2');assert blocked['result']['accepted'] is False
    equal(blocked['result']['healthBefore'],blocked['result']['healthAfter'])
    qa('cmd JTMoneyOwner team pvp');enabled=qa('pvp');assert enabled['result']['deny'] is False
    hurt=qa('hit JTMoneyOwner JTMoneyMember 2');assert hurt['result']['accepted'] is True
    assert hurt['result']['healthAfter'] < hurt['result']['healthBefore']
    qa('cmd JTMoneyOwner team pvp')
    send('gamemode creative JTMoneyMember')
    return [blocked['artifact'],hurt['artifact']]

def chest_cursor_shift_hotbar():
    qa('close JTMoneyOwner')
    send('item replace entity JTMoneyOwner hotbar.0 with minecraft:diamond[minecraft:custom_name=\'{"text":"Synthetic ledger diamond"}\',minecraft:lore=[\'{"text":"qa-only"}\'],minecraft:custom_data={fixture:"jtqa",case:"cursor"}] 12')
    time.sleep(.4)
    opened=qa('cmd JTMoneyOwner team ec');top=actor(opened['result'],'JTMoneyOwner')['slots']-36
    assert actor(opened['result'],'JTMoneyOwner')['menu'].endswith('TeamChests$Menu')
    busy=qa('cmd JTMoneyMember team ec');assert not actor(busy['result'],'JTMoneyMember')['menu'].endswith('TeamChests$Menu')
    picked=click('JTMoneyOwner',top+27);assert 'diamond' in actor(picked['result'],'JTMoneyOwner')['cursor']
    put=click('JTMoneyOwner',0);assert 'diamond' in put['result']['chest']['inventoryJson']
    assert actor(put['result'],'JTMoneyOwner')['cursor'].startswith('0 ')
    shifted=click('JTMoneyOwner',0,'QUICK_MOVE');assert 'diamond' in actor(shifted['result'],'JTMoneyOwner')['inventory']
    # Vanilla shift transfer scans from the final player slot, which is hotbar8.
    swapped=click('JTMoneyOwner',1,'SWAP',8)
    assert 'diamond' in swapped['result']['chest']['inventoryJson']
    taken=click('JTMoneyOwner',1,'QUICK_MOVE')
    closed=qa('close JTMoneyOwner');final=closed['result']
    assert actor(final,'JTMoneyOwner')['diskInventoryMatches'] is True
    assert 'pending=0' in final['status'] and final['chest']['generation'] >= 5
    return [picked['artifact'],put['artifact'],shifted['artifact'],swapped['artifact'],taken['artifact'],closed['artifact']]

def main():
    parser=argparse.ArgumentParser();parser.add_argument('stage',choices=('bank','bank-resume','gui','chest','resume','home-cap'));args=parser.parse_args()
    if args.stage in ('bank','bank-resume'):
        if args.stage=='bank' and not check('real-provider-synthetic-initialisation',initialise):return
        check('deposit-001',lambda:transfer_case('dep01','deposit','0.01',.01))
        check('deposit-0005-half-up',lambda:transfer_case('dep005','deposit','0.005',.01))
        check('withdraw-001',lambda:transfer_case('withdraw01','withdraw','0.01',.01))
        check('member-withdraw-stored-false',lambda:refused_case('member-denied','team bank withdraw 1','JTMoneyMember'))
        check('coowner-withdraw-stored-false',lambda:refused_case('co-denied','team bank withdraw 1','JTMoneyCo'))
        for token in ('0','-1','NaN','Infinity','0.001'):
            check('invalid-'+token,lambda token=token:refused_case('invalid','team bank deposit '+token))
        check('stable-operation-replay',replay)
        if args.stage=='bank':check('bank-cap-with-subcent-residue',cap)
    elif args.stage=='gui':
        check('native-bank-handler-and-prompt-once',gui_bank)
        check('native-settings-handler-and-tag-prompt',gui_settings)
        check('native-owner-transfer-confirmations',gui_transfer)
        check('kick-invite-native-accept-handler',kick_invite)
        check('home-warmup-movement-cancel-and-completion',home_cancel)
        check('same-team-pvp-actual-damage-mixin',pvp)
    elif args.stage=='chest':
        check('chest-one-viewer-cursor-shift-hotbar-playerdat',chest_cursor_shift_hotbar)
    elif args.stage=='resume':check('synthetic-actors-resume-no-wallet-reset',spawn_actors)
    else:
        check('bank-cap-with-subcent-residue',cap)
        check('home-warmup-movement-cancel-and-completion',home_cancel)

if __name__=='__main__':main()
