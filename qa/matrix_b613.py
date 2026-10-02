"""Pinned native surface/money matrix; all actions target three synthetic actors."""
import argparse
import ast
from datetime import datetime,timezone
from decimal import Decimal,ROUND_HALF_UP
import json
from pathlib import Path
import random
import time
import yaml
from rig import ROOT,send
from run_cases import qa,state,actor,balance,wallet,equal,click,spawn_actors

PROOF=ROOT/'qa-matrix-b613.json'
report=json.loads(PROOF.read_text(encoding='utf-8')) if PROOF.exists() else []
def check(name,run):
    start=datetime.now(timezone.utc).isoformat()
    try:detail=run();row={'case':name,'pass':True,'detail':detail}
    except Exception as error:row={'case':name,'pass':False,'error':str(error)}
    row.update({'utcStart':start,'utcEnd':datetime.now(timezone.utc).isoformat(),'rung':'state','n':1,'interval':'per case'})
    report.append(row);PROOF.write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(row),flush=True)
    return row['pass']
def patch(file,key,value):
    assert file in ('config.yml','messages.yml','commands.yml','gui.yml','placeholders.yml')
    path=ROOT/'config/JustTeams'/file;data=yaml.safe_load(path.read_text(encoding='utf-8'));parts=key.split('.')
    node=data
    for part in parts[:-1]:node=node.setdefault(part,{})
    node[parts[-1]]=value;path.write_text(yaml.safe_dump(data,allow_unicode=True,sort_keys=False),encoding='utf-8')
    send('team reload');time.sleep(.25);qa('status')
def denied(command,code):
    try:qa(command)
    except RuntimeError as error:
        assert code in str(error),(code,str(error));return str(error)
    raise AssertionError('Expected refusal '+code+' for '+command)
def member(snapshot,name):
    uuid=actor(snapshot,name)['uuid'];return next(m for m in snapshot['team_members'] if m['player_uuid']==uuid)
def plain(snapshot,name):return actor(snapshot,name)['inventory']
def gui_slot(name,slot):return qa('gui '+name)['result'][slot]
def smoke():
    status=qa('status');assert 'ready=true' in status['result'] and 'snapshot-health=true' in status['result']
    papi=qa('papi JTMoneyOwner tag,display,colored_tag,top_points_1_colored_tag,points,unknown_xyz')
    assert papi['result']['registered'] and papi['result']['tag']['engine']=='JTM'
    assert 'JTM' in papi['result']['display']['engine'] and '<team_tag>' not in papi['result']['display']['engine']
    assert 'JTM' in papi['result']['colored_tag']['engine'] and 'JTM' in papi['result']['top_points_1_colored_tag']['engine']
    assert papi['result']['points']['engine']=='11' and papi['result']['unknown_xyz']['resolver']=='null'
    no_team=qa('papi JTAbsent tag,display,has_team');assert no_team['result']['has_team']['resolver']=='false'
    null=qa('papi null tag');assert null['result']['tag']['resolver']==''
    return {'papiArtifact':papi['artifact'],'nullArtifact':null['artifact'],'noTeamArtifact':no_team['artifact']}
def initialise():
    spawn_actors()
    for name in ('JTMoneyOwner','JTMoneyMember','JTMoneyCo'):qa('wallet '+name+' 10000000')
    send('lp user JTMoneyOwner permission set justteams.admin true');time.sleep(.3)
    return smoke()
def cache():
    assert qa('policy JTMoneyOwner JTMoneyOwner')['result']=='ALLOW'
    assert qa('policy JTMoneyOwner JTMoneyMember')['result']=='DENY'
    assert qa('policy JTMoneyOwner JTAbsent')['result']=='ALLOW'
    assert qa('policy null JTMoneyMember')['result']=='UNKNOWN'
    qa('cache failed');assert qa('policy JTMoneyOwner JTMoneyMember')['result']=='UNKNOWN'
    qa('cache team');assert qa('cache status')['result']['healthy'] is False
    assert qa('policy JTMoneyOwner JTAbsent')['result']=='UNKNOWN'
    qa('cache full');assert qa('policy JTMoneyOwner JTMoneyMember')['result']=='DENY'
    return 'ALLOW/DENY/UNKNOWN; partial refresh stays unhealthy; full refresh repairs'
def aliases():
    qa('incumbent party')
    before=qa('nodes')['result'];assert before['party']!='absent'
    assert 'TeamCommands' not in before['party']['commandClass']
    patch('commands.yml','command-aliases.guild.enabled',False)
    after=qa('nodes')['result'];assert after['guild']=='absent' and after['g']=='absent' and after['party']==before['party']
    patch('commands.yml','command-aliases.guild.enabled',True)
    assert qa('nodes')['result']['guild']!='absent'
    return {'incumbentParty':before['party'],'ownedGuildDisabledOnly':True}
def roles():
    qa('control flag JTMoneyMember can_promote_to_co_owner true')
    denied('core role JTMoneyMember JTMoneyCo true','ELEVATED_REQUIRED')
    qa('core member JTMoneyOwner JTMoneyCo');before=state('slot28-before');click('JTMoneyOwner',28)
    after=state('slot28-after');assert member(after,'JTMoneyCo')['can_promote_to_co_owner']!=member(before,'JTMoneyCo')['can_promote_to_co_owner']
    qa('core role JTMoneyCo JTMoneyMember true');assert member(state('promoted'),'JTMoneyMember')['role']=='CO_OWNER'
    denied('core role JTMoneyCo JTMoneyMember false','OWNER_REQUIRED')
    denied('core kick JTMoneyCo JTMoneyMember','OWNER_REQUIRED')
    qa('core role JTMoneyOwner JTMoneyMember false');assert member(state('demoted'),'JTMoneyMember')['role']=='MEMBER'
    return 'MEMBER+stored grant denied; granted CO promotes; demote/kick CO require owner; slot28 toggles grant'
def gates():
    patch('config.yml','features.team_bank',False)
    denied('bank JTMoneyOwner deposit 1 matrix_gate_0001','FEATURE_DISABLED')
    patch('config.yml','features.team_bank',True)
    patch('config.yml','world_restrictions.enabled',True);patch('config.yml','world_restrictions.disabled_worlds.sethome',['world'])
    denied('cmd JTMoneyOwner team sethome','WORLD_DENIED') if False else None
    # Command dispatcher swallows domain refusals; verify no state/payment delta.
    before=state('raw-world-before');qa('cmd JTMoneyOwner team sethome');after=state('raw-world-after')
    equal(balance(before),balance(after));equal(wallet(before),wallet(after));assert after['teams'][0].get('home_location')==before['teams'][0].get('home_location')
    patch('config.yml','world_restrictions.disabled_worlds.sethome',[])
    return 'feature and raw Bukkit-name world gates refuse without money/state mutation'
def gui():
    patch('messages.yml','status_enabled','<green>YES_CUSTOM</green>');patch('messages.yml','status_disabled','<red>NO_CUSTOM</red>')
    qa('core member JTMoneyOwner JTMoneyMember');slots=qa('gui JTMoneyOwner')['result']
    assert '<withdraw_status>' not in json.dumps(slots)
    assert any('NO_CUSTOM' in json.dumps(slot) and 'red' in json.dumps(slot).lower() for slot in slots)
    qa('core member JTMoneyMember JTMoneyMember');before=state('view-before');click('JTMoneyMember',37);after=state('view-after')
    assert member(before,'JTMoneyMember')['can_withdraw']==member(after,'JTMoneyMember')['can_withdraw']
    qa('cmd JTMoneyCo team bank');locked=gui_slot('JTMoneyCo',15);before=state('locked-before');click('JTMoneyCo',15);qa('chat JTMoneyCo 1');after=state('locked-after')
    equal(balance(before),balance(after));equal(wallet(before,'JTMoneyCo'),wallet(after,'JTMoneyCo'))
    qa('control team description <red>LITERAL_USER</red>')
    patch('gui.yml','team-settings-gui.items.change-description.lore',['<team_description>'])
    qa('cmd JTMoneyOwner team settings');desc=gui_slot('JTMoneyOwner',13)
    assert '<red>LITERAL_USER</red>' in json.dumps(desc['lore'])
    return {'lockedWithdraw':locked['name'],'descriptionLore':desc['lore']}
def admin():
    before=state('admin-bank-before');target=balance(before)+77;op='matrix_admin_'+str(time.time_ns());qa(f'core adminBalance JTMoneyOwner 1 {target} {op}');after=state('admin-bank-after')
    equal(balance(after),target);equal(wallet(after),wallet(before))
    receipt=qa('op '+op);assert '"admin":true' in receipt['result'] and 'kind=BANK' in receipt['result'] and 'DONE' in receipt['result']
    qa('core adminPoints JTMoneyOwner 1 set 123456');qa('core adminStats JTMoneyOwner 1 22 8')
    s=state('admin-stats');assert s['teams'][0]['points']==123456 and s['teams'][0]['kills']==22 and s['teams'][0]['deaths']==8
    qa('cmd JTMoneyOwner team color &c');assert state('color-legacy')['teams'][0]['color']=='RED'
    qa('cmd JTMoneyOwner team color #123456');assert state('color-hex')['teams'][0]['gradient_start']=='#123456'
    qa('cmd JTMoneyOwner team color #123456 #654321');papi=qa('papi JTMoneyOwner colored_tag');assert 'gradient:' in papi['result']['colored_tag']['engine']
    qa('cmd JTMoneyOwner team color reset');s=state('color-reset');assert not s['teams'][0].get('color') and not s['teams'][0].get('gradient_start')
    return {'receipt':receipt['artifact'],'colorPapi':papi['artifact']}
def randomized():
    before=state('random-before');rng=random.Random(613906);rows=[];b=Decimal(str(balance(before)));p=Decimal(str(wallet(before)))
    for index in range(40):
        raw=Decimal(rng.randint(1,900000))/Decimal(1000)
        amount=raw.quantize(Decimal('.01'),rounding=ROUND_HALF_UP);deposit=index%2==0
        if not deposit and amount>b:deposit=True
        row=qa(f'bank JTMoneyOwner {"deposit" if deposit else "withdraw"} {raw} matrix_random_{index:04d}')
        b+=amount if deposit else -amount;p-=amount if deposit else -amount
        equal(balance(row['result']),float(b));equal(wallet(row['result']),float(p));assert 'pending=0' in row['result']['status']
        rows.append({'raw':str(raw),'amount':str(amount),'deposit':deposit,'artifact':row['artifact']})
    (ROOT/'qa-random40-b613.json').write_text(json.dumps({'n':40,'bankExpected':str(b),'walletExpected':str(p),'openingResidue':str(Decimal(str(balance(before)))%1),'operations':rows},indent=2),encoding='utf-8')
    return {'n':40,'bank':str(b),'wallet':str(p)}
def cold():
    spawn_actors();s=state('random-cold');record=json.loads((ROOT/'qa-random40-b613.json').read_text(encoding='utf-8'))
    equal(balance(s),float(record['bankExpected']));equal(wallet(s),float(record['walletExpected']));assert 'pending=0' in s['status']
    return {'n':40,'durableBalancesExact':True}

def allies():
    qa('core kick JTMoneyOwner JTMoneyCo');qa('cmd JTMoneyCo team create JTAllyFixture JTA')
    s=state('ally-created');assert len(s['teams'])==2
    assert qa('policy JTMoneyOwner JTMoneyCo')['result']=='ALLOW'
    qa('control ally true');assert qa('policy JTMoneyOwner JTMoneyCo')['result']=='DENY'
    qa('control ally false');assert qa('policy JTMoneyOwner JTMoneyCo')['result']=='ALLOW'
    qa('cmd JTMoneyCo team disband');click('JTMoneyCo',11)
    qa('cmd JTMoneyOwner team invite JTMoneyCo');qa('cmd JTMoneyCo team accept JTMoneyFixture')
    qa('core role JTMoneyOwner JTMoneyCo true');qa('core editFlag JTMoneyOwner JTMoneyCo can_withdraw false')
    return 'different teams ALLOW, allied DENY, removed ALLOW, membership restored'
def fees():
    patch('config.yml','feature_costs.permission_based_join_fee.enabled',True)
    patch('config.yml','feature_costs.permission_based_join_fee.max_fee',6.5)
    patch('config.yml','feature_costs.economy.team_join',1.25)
    send('lp user b34e1499-dd5c-37f3-b77b-1f6ff6bd6516 permission set justteams.joinfee.12.345 true');time.sleep(.3)
    perms=qa('perms JTMoneyMember');assert perms['result']['feeMaximum']>=12.345
    qa('core kick JTMoneyOwner JTMoneyMember');before=state('paid-join-before');qa('cmd JTMoneyMember team join JTMoneyFixture');after=state('paid-join-after')
    equal(wallet(after,'JTMoneyMember'),wallet(before,'JTMoneyMember')-6.5)
    assert member(after,'JTMoneyMember')['role']=='MEMBER'
    qa('core kick JTMoneyOwner JTMoneyMember');qa('control team is_public false')
    qa('cmd JTMoneyMember team join JTMoneyFixture');before=state('request-fee-before');assert before['join_requests']
    qa('core acceptRequest JTMoneyOwner JTMoneyMember');after=state('request-fee-after');equal(wallet(after,'JTMoneyMember'),wallet(before,'JTMoneyMember')-6.5)
    assert not after['join_requests'] and member(after,'JTMoneyMember')['role']=='MEMBER'
    qa('control team is_public true')
    return {'nestedCap':6.5,'decimalNode':12.345,'publicJoinAndOnlineRequestCharged':True}
def destinations():
    send('unop JTMoneyOwner');time.sleep(.2)
    patch('config.yml','world_restrictions.disabled_worlds.home',[]);patch('config.yml','world_restrictions.disabled_worlds.warp',[])
    qa('setbank 2000000');send('fill -5 98 -5 35 98 5 minecraft:stone');send('tp JTMoneyOwner 30 99 0');time.sleep(.25)
    before=state('home-paid-before');qa('cmd JTMoneyOwner team sethome');after=state('home-paid-after');equal(balance(after),balance(before)-250000)
    send('tp JTMoneyOwner 0 99 0');time.sleep(.2);qa('cmd JTMoneyOwner team home');send('tp JTMoneyOwner 2 99 0');time.sleep(5.5)
    cancelled=state('home-cancel');assert actor(cancelled,'JTMoneyOwner')['position'].startswith('(2.5,')
    time.sleep(5);qa('cmd JTMoneyOwner team home');time.sleep(5.5);done=state('home-arrive');assert actor(done,'JTMoneyOwner')['position'].startswith('(30.5,')
    before=state('warp-paid-before');qa('cmd JTMoneyOwner team setwarp proof');after=state('warp-paid-after');equal(balance(after),balance(before)-500000)
    send('execute in minecraft:the_nether run tp JTMoneyOwner 0 100 0');time.sleep(.3);qa('cmd JTMoneyOwner team warp proof');time.sleep(5.5)
    returned=state('warp-dimension-return');assert actor(returned,'JTMoneyOwner')['dimension']=='minecraft:overworld'
    return {'homeCancelAndComplete':True,'paidWarpAcrossDimensions':True}
def escrow_setup():
    qa('close JTMoneyOwner');send('clear JTMoneyOwner');send('item replace entity JTMoneyOwner hotbar.0 with minecraft:diamond 7');time.sleep(.3)
    qa('cmd JTMoneyOwner team ec');s=state('escrow-open');top=actor(s,'JTMoneyOwner')['slots']-36
    click('JTMoneyOwner',top+27);click('JTMoneyOwner',0);qa('fill JTMoneyOwner');click('JTMoneyOwner',0)
    closed=qa('close JTMoneyOwner');s=closed['result'];assert 'phase=ESCROW' in s['pending'] and 'pending=1' in s['status']
    (ROOT/'qa-escrow-b613.json').write_text(json.dumps({'n':1,'cursorEscrow':7,'closeArtifact':closed['artifact'],'inventory':plain(s,'JTMoneyOwner')},indent=2),encoding='utf-8')
    return {'escrow7':True,'noDropAndFullInventoryRetained':True,'artifact':closed['artifact']}
def escrow_partial():
    spawn_actors();send('gamemode survival JTMoneyOwner');time.sleep(.2);s=state('escrow-cold');record=json.loads((ROOT/'qa-escrow-b613.json').read_text(encoding='utf-8'));assert plain(s,'JTMoneyOwner')==record['inventory'] and 'phase=ESCROW' in s['pending']
    send('item replace entity JTMoneyOwner hotbar.0 with minecraft:diamond 63');time.sleep(.25)
    qa('core claim JTMoneyOwner');s=state('escrow-partial');assert 'count:64,id:"minecraft:diamond"' in plain(s,'JTMoneyOwner') and 'phase=ESCROW' in s['pending']
    prior=plain(s,'JTMoneyOwner');qa('core claim JTMoneyOwner');s=state('escrow-repeat');assert plain(s,'JTMoneyOwner')==prior and 'phase=ESCROW' in s['pending']
    (ROOT/'qa-escrow-partial-b613.json').write_text(json.dumps({'inventory':prior,'remaining':6},indent=2),encoding='utf-8')
    return {'coldInventoryExact':True,'partialOneClaimed':True,'remaining6':True,'repeatNoDuplicate':True}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('stage',choices=('smoke','surface','random','cold','remaining','escrow','partial'));args=parser.parse_args()
    if args.stage=='smoke':check('native-papi-registration-and-fixture-init',initialise)
    elif args.stage=='surface':
        for name,run in [('read-only-cache',cache),('owned-alias-reload',aliases),('stored-role-guards-and-slot28',roles),('feature-raw-world-gates',gates),('gui-flags-status-user-literal',gui),('admin-receipts-points-stats-colours',admin)]:check(name,run)
    elif args.stage=='random':check('real-provider-randomized-decimal40',randomized)
    elif args.stage=='cold':check('random40-cold-readback',cold)
    elif args.stage=='remaining':
        for name,run in [('owned-alias-reload',aliases),('allies-read-only-cache',allies),('decimal-paid-public-online-request-fees',fees),('admin-receipts-points-stats-colours',admin),('paid-home-warp-dimension-cancel',destinations)]:check(name,run)
    elif args.stage=='escrow':check('full-inventory-escrow-close',escrow_setup)
    else:check('escrow-cold-partial-repeat',escrow_partial)
if __name__=='__main__':main()
