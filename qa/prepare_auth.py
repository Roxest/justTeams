"""Fresh native auth account; random fixture secret stays private outside git/rig."""
import json
import os
from pathlib import Path
import secrets
import subprocess
from rig import ROOT,marked,JAVA
marked()
assert not (ROOT/'config/pxc-auth.json').exists(), 'Refuse overwrite of auth config'
(ROOT/'.justteams-real-auth-fixture').write_text('synthetic-real-auth-loopback\n',encoding='ascii')
private=Path('F:/Pixelclub/scratch/justteams-auth-private-20261003')
private.mkdir(exist_ok=True)
credential=private/'owner-credential.json'
assert not credential.exists(), 'Refuse overwrite of fixture credential'
secret=secrets.token_hex(10)
credential.write_text(json.dumps({'username':'JTMoneyOwner','password':secret}),encoding='utf-8')
subprocess.run(['icacls',str(credential),'/inheritance:r','/grant:r',os.environ['USERNAME']+':F'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
env=os.environ.copy();env['JTQA_NATIVE_AUTH_PASSWORD']=secret
bootstrap=ROOT/'fixture-bootstrap'
subprocess.run([str(JAVA.with_name('javac.exe')),'-d',str(bootstrap),str(Path(__file__).with_name('AuthFixtureBootstrap.java'))],check=True)
cp=';'.join([str(bootstrap),'F:/Pixelclub/justteams-fabric-pxc/libs/JustTeams-Fabric-2.5.5.jar',str(ROOT/'mods/pxc-auth-0.2.8.jar')])
subprocess.run([str(JAVA),'-cp',cp,'AuthFixtureBootstrap',str(ROOT)],env=env,check=True)
config={'provider':'pxc-auth','native':{'enabled':True,'shadow':False,'database':{'url':'jdbc:h2:file:'+str(ROOT/'config/pxc-auth-qa/accounts').replace('\\','/')+';MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_ON_EXIT=FALSE','user':'sa','driver':'eu.kotori.justTeams.libs.h2.Driver','table':'authme','pool_size':2,'connect_timeout_ms':5000},'first_spawn':{'dimension':'minecraft:overworld','x':0.5,'y':99.0,'z':0.5,'yaw':0.0,'pitch':0.0},'sessions':{'enabled':False,'timeout_minutes':1},'first_spawn_teleport':False,'strip_op_from_unregistered':False,'antibot':{'enabled':False},'limbo':{'timeout_seconds':120,'quick_command_ms':0,'gate_non_typed_commands':True},'limits':{'max_join_per_ip':10,'max_login_per_ip':10,'max_reg_per_ip':10}}}
(ROOT/'config/pxc-auth.json').write_text(json.dumps(config,indent=2),encoding='utf-8')
print('Local native auth ready: separate H2 only, sessions disabled; PxcEyes2 unregistered')
