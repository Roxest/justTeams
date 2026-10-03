"""Native password login over loopback vanilla TCP; synthetic secret never printed."""
import hashlib
import json
from pathlib import Path
import socket
import struct
import threading
import time
import uuid
import zlib
import argparse
from rig import ROOT,marked
from run_cases import qa,state
parser=argparse.ArgumentParser();parser.add_argument('--cap-tests',action='store_true');parser.add_argument('--batch',choices=('unlimited','lower-refund','tier','positive','chest-pre-growth','chest-pre-growth-resume','chest-growth'));parser.add_argument('--chest-tier',type=int,default=2);options=parser.parse_args()
marked()
credential=json.loads(Path('F:/Pixelclub/scratch/justteams-auth-private-20261003/owner-credential.json').read_text(encoding='utf-8'))
name=credential['username'];assert name=='JTMoneyOwner';secret=credential['password'];profile=uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3)
compression=None
def vint(value):
    result=bytearray()
    while True:
        part=value&127;value>>=7;result.append(part|(128 if value else 0))
        if not value:return bytes(result)
def integer(data,offset=0):
    result=0
    for shift in range(0,35,7):
        value=data[offset];offset+=1;result|=(value&127)<<shift
        if not value&128:return result,offset
    raise ValueError('invalid integer')
def text(value):
    body=value.encode();return vint(len(body))+body
def exact(s,count):
    data=bytearray()
    while len(data)<count:
        part=s.recv(count-len(data))
        if not part:raise EOFError()
        data.extend(part)
    return bytes(data)
def receive(s):
    length=bytearray()
    while True:
        part=exact(s,1);length+=part
        if not part[0]&128:break
    count,_=integer(length);assert count<8_000_000;body=exact(s,count)
    if compression is not None:
        count,pos=integer(body);body=zlib.decompress(body[pos:]) if count else body[pos:]
    packet,pos=integer(body);return packet,body[pos:]
lock=threading.Lock()
def send(s,packet,data=b''):
    body=vint(packet)+data
    if compression is not None:body=vint(len(body))+zlib.compress(body) if len(body)>=compression else b'\0'+body
    with lock:s.sendall(vint(len(body))+body)

proof={'applicationSha256':hashlib.sha256((ROOT/'mods/JustTeams-2.5.5+pxc.1.jar').read_bytes()).hexdigest(),'identity':str(profile),'rung':'state','testActorsFlag':False,'provider':'pxc-auth','method':'actual vanilla password command'}
with socket.create_connection(('127.0.0.1',25655),timeout=10) as s:
    s.settimeout(10);send(s,0,vint(767)+text('127.0.0.1')+struct.pack('>H',25655)+vint(2));send(s,0,text(name)+profile.bytes)
    phase='login'
    while phase!='play':
        packet,data=receive(s)
        if phase=='login':
            if packet==3:compression,_=integer(data)
            elif packet==2:assert uuid.UUID(bytes=data[:16])==profile;send(s,3);phase='configuration'
            elif packet==4:request,_=integer(data);send(s,2,vint(request)+b'\0')
            elif packet==0:raise RuntimeError('login disconnected before native session')
        else:
            if packet==14:send(s,7,vint(0))
            elif packet==3:send(s,3);phase='play'
            elif packet==4:send(s,4,data)
            elif packet==5:send(s,5,data)
            elif packet==2:raise RuntimeError('configuration disconnected before native session')
    alive=True
    def pump():
        while alive:
            try:packet,data=receive(s)
            except (EOFError,OSError):break
            # Actual play keepalive and teleport confirmations, ids from official1.21.1 protocol.
            if packet==0x26:send(s,0x18,data)
            if packet==0x40 and len(data)>=33:
                teleport,_=integer(data,33);send(s,0,vint(teleport))
    worker=threading.Thread(target=pump,daemon=True);worker.start()
    deadline=time.monotonic()+15
    while time.monotonic()<deadline:
        status=qa('authstate '+name)
        if status['result']['status']=='UNAUTHENTICATED':break
        time.sleep(.25)
    else:raise AssertionError('Native unauthenticated session not reached')
    assert not status['result']['testActorsFlag'] and status['result']['playerClass']!='carpet.patches.EntityPlayerMPFake'
    quick=bool(options.batch and options.batch.startswith('chest'))
    if not quick:
        before=state('real-auth-unauth-before');public=before['teams'][0]['is_public'];send(s,4,text('team public'));time.sleep(.5)
        denied=state('real-auth-unauth-after');assert denied['teams'][0]['is_public']==public
        proof['unauthenticatedDenied']={'authArtifact':status['artifact'],'stateArtifact':denied.get('artifact','server-state'), 'session':status['result']['session']}
    send(s,4,text('login '+secret))
    deadline=time.monotonic()+12
    while time.monotonic()<deadline:
        status=qa('authstate '+name)
        if status['result']['status']=='AUTHENTICATED':break
        time.sleep(.25)
    else:raise AssertionError('Native password authentication not reached')
    if not quick:
        send(s,4,text('team public'));time.sleep(.5);changed=state('real-auth-authorized-action');assert changed['teams'][0]['is_public']!=public
        send(s,4,text('team public'));time.sleep(.5);restored=state('real-auth-action-restored');assert restored['teams'][0]['is_public']==public
        proof['authenticatedAuthorized']={'authArtifact':status['artifact'],'sameSession':status['result']['session']==proof['unauthenticatedDenied']['session'],'reversiblePublicToggle':True}
    else:proof['authenticatedNativeSession']={'authArtifact':status['artifact'],'session':status['result']['session'],'status':status['result']['status']}
    if options.batch:
        from matrix_b613 import patch
        from run_cases import wallet,balance,equal
        import yaml
        if options.batch=='unlimited':
            patch('config.yml','team_bank.max_balance',0);qa('setbank 999999999.995');base=state('batch-zero-before');qa('bank JTMoneyOwner deposit 1 qa_zero_'+str(time.time_ns()));assert balance(state('batch-zero-after'))>1000000000;qa('bank JTMoneyOwner withdraw 1 qa_zero_w_'+str(time.time_ns()))
            patch('config.yml','team_bank.max_balance',-1);qa('bank JTMoneyOwner deposit 1 qa_negative_'+str(time.time_ns()));assert balance(state('batch-negative-after'))>1000000000;qa('bank JTMoneyOwner withdraw 1 qa_negative_w_'+str(time.time_ns()));equal(balance(state('batch-unlimited-restored')),balance(base))
        elif options.batch=='lower-refund':
            patch('config.yml','team_bank.max_balance',1000);qa('setbank 1005.005');base=state('batch-lowered-before');qa('bank JTMoneyOwner deposit .01 qa_lowered_'+str(time.time_ns()));after=state('batch-lowered-after');equal(balance(after),balance(base));equal(wallet(after),wallet(base));assert 'pending=0' in after['status'];comp=qa('refundcase');equal(balance(comp['result']['state']),1005.005);assert comp['result']['finished'] and 'pending=0' in comp['result']['state']['status']
        elif options.batch=='tier':
            path=ROOT/'config/JustTeams/config.yml';cfg=yaml.safe_load(path.read_text(encoding='utf-8'));cfg['team_upgrades']['tiers']={int(k):v for k,v in cfg['team_upgrades']['tiers'].items()};cfg['team_upgrades']['tiers'][2].update({'cost':250000,'max_members':17});cfg['team_bank']['max_balance']=1000000000;path.write_text(yaml.safe_dump(cfg,sort_keys=False,allow_unicode=True),encoding='utf-8');from rig import send as console;console('team reload');time.sleep(.2);qa('control tier 1');qa('setbank 1000000');qa('core upgrade JTMoneyOwner');after=state('batch-tier-after');assert after['teams'][0]['tier']==2;equal(balance(after),750000);assert qa('capacity JTMoneyOwner')['result']==17
        elif options.batch=='positive':
            patch('config.yml','team_bank.max_balance',1000000000);qa('setbank 123.005');base=state('batch-positive-before');qa('bank JTMoneyOwner deposit .01 qa_pos_'+str(time.time_ns()));qa('bank JTMoneyOwner withdraw .01 qa_pos_w_'+str(time.time_ns()));after=state('batch-positive-after');equal(balance(after),balance(base));equal(wallet(after),wallet(base));proof['coldExpected']={'bank':balance(after),'wallet':wallet(after)}
        elif options.batch=='chest-pre-growth':
            from run_cases import actor
            from rig import send as console
            base=state('chest-growth-money-before');qa('control tier 1')
            console('item replace entity JTMoneyOwner hotbar.0 with minecraft:diamond[minecraft:custom_name=\'{"text":"Tier growth rich stock"}\',minecraft:lore=[\'{"text":"synthetic local only"}\'],minecraft:custom_data={fixture:"jtqa",case:"tier-growth"}] 1')
            time.sleep(.2);send(s,4,text('team ec'));time.sleep(.25)
            opened=state('chest-tier1-open');assert actor(opened,'JTMoneyOwner')['slots']-36==27
            qa('click JTMoneyOwner 54 PICKUP 0');stock=qa('click JTMoneyOwner 0 PICKUP 0')['result'];assert 'Tier growth rich stock' in stock['chest']['inventoryJson'];assert actor(stock,'JTMoneyOwner')['cursor'].startswith('0 ')
            qa('close JTMoneyOwner');qa('control tier 2');send(s,4,text('team ec'));time.sleep(.25)
            failed=state('chest-tier2-pre-fix-open');assert actor(failed,'JTMoneyOwner')['slots']-36==27
            assert failed['chest']['inventoryJson']==stock['chest']['inventoryJson'];assert failed['chest']['originalSha256']==stock['chest']['originalSha256'];assert actor(failed,'JTMoneyOwner')['cursor'].startswith('0 ')
            final=qa('close JTMoneyOwner')['result'];equal(balance(final),balance(base));equal(wallet(final),wallet(base));assert 'pending=0' in final['status']
            proof['chestPreGrowth']={'tier':2,'expectedSlots':36,'actualSlots':27,'richItemPreserved':True,'sourceSha256':final['chest']['originalSha256'],'generation':final['chest']['generation'],'cursorEmpty':True,'moneyUnchanged':True,'stateArtifact':failed.get('artifact'),'coldExpected':final['chest']}
        elif options.batch in ('chest-pre-growth-resume','chest-growth'):
            from run_cases import actor
            tier=options.chest_tier;assert tier in (1,2,3,4,5)
            base=state('chest-resume-before');old=base['chest'];qa('control tier '+str(tier));send(s,4,text('team ec'));time.sleep(.2)
            previous=json.loads(old['inventoryJson']);opened=state('chest-resume-open');top=actor(opened,'JTMoneyOwner')['slots']-36;expected=27 if options.batch=='chest-pre-growth-resume' else max({1:27,2:36,3:45,4:54,5:54}[tier],previous['size'])
            assert top==expected,(top,expected);assert 'Tier growth rich stock' in opened['chest']['inventoryJson'];assert actor(opened,'JTMoneyOwner')['cursor'].startswith('0 ')
            rich=next(i for i,item in enumerate(previous['slots']) if 'Tier growth rich stock' in json.dumps(item));target={1:53,2:33,3:44,4:53,5:53}[tier]
            if options.batch=='chest-growth' and target!=rich:
                picked=qa('click JTMoneyOwner '+str(rich)+' PICKUP 0')['result'];assert not actor(picked,'JTMoneyOwner')['cursor'].startswith('0 ')
                placed=qa('click JTMoneyOwner '+str(target)+' PICKUP 0')['result'];saved=json.loads(placed['chest']['inventoryJson']);assert saved['size']==expected and saved['slots'][target]==previous['slots'][rich];assert actor(placed,'JTMoneyOwner')['cursor'].startswith('0 ');assert placed['chest']['generation']==old['generation']+2
            final=qa('close JTMoneyOwner')['result'];equal(balance(final),balance(base));equal(wallet(final),wallet(base));assert 'pending=0' in final['status'];assert final['chest'].get('originalSha256')==old.get('originalSha256')
            proof['chestGrowth']={'tier':tier,'actualSlots':top,'persistedSize':json.loads(final['chest']['inventoryJson'])['size'],'richItemPreserved':True,'stockSlot':target if options.batch=='chest-growth' else rich,'componentsCountExact':True,'sourceSha256':final['chest'].get('originalSha256'),'generation':final['chest']['generation'],'cursorEmpty':True,'moneyUnchanged':True,'openTransport':'actual vanilla TCP','clickTransport':'native server handler','coldExpected':final['chest']}
        proof['batchPassed']=options.batch
    if options.cap_tests:
        from matrix_b613 import patch
        from run_cases import wallet,balance,equal
        import yaml
        patch('config.yml','team_bank.max_balance',0);qa('setbank 999999999.995');base=state('unlimited-zero-before')
        qa('bank JTMoneyOwner deposit 10 qa_cap_zero_deposit');high=state('unlimited-zero-after');equal(balance(high),balance(base)+10);assert balance(high)>1000000000
        qa('bank JTMoneyOwner withdraw 10 qa_cap_zero_withdraw');equal(balance(state('unlimited-zero-restored')),balance(base))
        patch('config.yml','team_bank.max_balance',-1);qa('bank JTMoneyOwner deposit 1 qa_cap_negative_deposit');assert balance(state('unlimited-negative-after'))>1000000000;qa('bank JTMoneyOwner withdraw 1 qa_cap_negative_withdraw')
        patch('config.yml','team_bank.max_balance',1000);before=state('lowered-cap-before');qa('bank JTMoneyOwner deposit .01 qa_lowered_cap_refusal');after=state('lowered-cap-after');equal(balance(after),balance(before));equal(wallet(after),wallet(before));assert 'pending=0' in after['status']
        qa('setbank 1005.005');comp=qa('refundcase');assert comp['result']['finished'];equal(balance(comp['result']['state']),1005.005);assert 'pending=0' in comp['result']['state']['status']
        # Preserve integer YAML keys; a string-key fixture would not test numeric fallback.
        path=ROOT/'config/JustTeams/config.yml';cfg=yaml.safe_load(path.read_text(encoding='utf-8'));tiers=cfg['team_upgrades']['tiers'];cfg['team_upgrades']['tiers']={int(k):v for k,v in tiers.items()};cfg['team_upgrades']['tiers'][2].update({'cost':250000,'max_members':17});cfg['team_bank']['max_balance']=1000000000;path.write_text(yaml.safe_dump(cfg,sort_keys=False,allow_unicode=True),encoding='utf-8')
        from rig import send as console
        console('team reload');time.sleep(.25);qa('control tier 1');qa('setbank 1000000');qa('core upgrade JTMoneyOwner');tier=state('integer-tier-after');assert tier['teams'][0]['tier']==2;equal(balance(tier),750000);assert qa('capacity JTMoneyOwner')['result']==17
        # One actual positive-cap provider pair, retained for exact cold readback.
        qa('setbank 123.005');small=state('positive-smoke-before');qa('bank JTMoneyOwner deposit .01 qa_positive_deposit');qa('bank JTMoneyOwner withdraw .01 qa_positive_withdraw');small_after=state('positive-smoke-after');equal(balance(small_after),balance(small));equal(wallet(small_after),wallet(small))
        proof['capTierTests']={'zeroNegativeUnlimited':True,'reloadAndLoweredPositiveRefusal':True,'grandfatheredCompensation1005_005':True,'integerTier1To2Cost250kCapacity17':True,'positiveProviderPair':True,'bankExpected':balance(small_after),'walletExpected':wallet(small_after)}
    alive=False
artifact=ROOT/('qa-auth-batch-'+options.batch+('-tier'+str(options.chest_tier) if options.batch=='chest-growth' else '')+'.json' if options.batch else 'qa-real-auth.json');artifact.write_text(json.dumps(proof,indent=2),encoding='utf-8')
print(json.dumps({'actualNativePasswordAuth':True,'publicToggleProof':not quick,'testActorsFlag':False,'artifact':str(artifact)}))
