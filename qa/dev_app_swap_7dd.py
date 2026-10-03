"""Root-delegated DEV app-only cold swap. Every phase requires the prior proof; no rollback/start on failure."""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import time

WINDOW=Path('F:/Pixelclub/pxctools-fabric/port/runtime/dev_window.py')
spec=importlib.util.spec_from_file_location('dev_window',WINDOW);window=importlib.util.module_from_spec(spec);spec.loader.exec_module(window)
EXPECTED='2026-10-03T02:37:30.739492594Z'
RELEASE='root-jt-runtime-7dd284fd-app-only-coldswap-20261003'
OLD='6864630f29a2b33cc0299e3cfcbca4335a1916e459c8208b8460165835567487'
NEW='7dd284fdeaa3619feafd21b972b5c6fa4a90999f7b93d2c55d23ad8bc8e4d2c9'
SOURCE=Path('F:/Pixelclub/scratch/justteams-cutover-20261002/artifacts/JustTeams-2.5.5+pxc.1-7dd284fd.jar')
KIT=Path('F:/Pixelclub/scratch/justteams-cutover-20261002/private-dev-canary-20261003');KIT.mkdir(exist_ok=True)
APP=window.DEV_ROOT+'/mods/JustTeams-2.5.5+pxc.1.jar'
REPORT=json.loads(Path('F:/Pixelclub/scratch/justteams-cutover-20261002/private-gui-stage/sanitization-report.json').read_text(encoding='utf-8'))

def write(name,value):
    (KIT/('swap7dd-'+name+'.json')).write_text(json.dumps(value,indent=2),encoding='utf-8')
def read(name):return json.loads((KIT/('swap7dd-'+name+'.json')).read_text(encoding='utf-8'))
def remote(client,command):
    _,out,err=client.exec_command(command,timeout=30);data=out.read();err.read()
    if out.channel.recv_exit_status()!=0:raise RuntimeError('remote metadata/mutation command failed')
    return data.decode().strip()
def snapshot(client,cold=False):
    code='''from pathlib import Path
import hashlib,json
def h(p):
 d=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):d.update(b)
 return d.hexdigest()
paths=list(Path('mods').glob('*.jar'))+list(Path('plugins').glob('*.jar'))
paths+=list(Path('config/JustTeams').glob('*.yml'))
paths+=[p for p in [Path('server.properties'),Path('plugins/AuthMe/config.yml'),Path('plugins/LuckPerms/config.yml')] if p.is_file()]
''' + ("paths+=list(Path('config/JustTeams/data').glob('*'))\n" if cold else '') + '''print(json.dumps({str(p):{'sha256':h(p),'size':p.stat().st_size,'mtime_ns':p.stat().st_mtime_ns,'uid':p.stat().st_uid,'gid':p.stat().st_gid,'mode':p.stat().st_mode&511} for p in paths if p.is_file()}))'''
    cmd='cd '+window.DEV_ROOT+' && python3 -c '+window.load_access().sh_quote("import base64;exec(base64.b64decode('"+base64.b64encode(code.encode()).decode()+"'))")
    return json.loads(remote(client,cmd))
def verify_config(snap):
    for name,item in REPORT['outputs'].items():assert snap['config/JustTeams/'+name]['sha256']==item['sha256'],'private-six hash changed'
def cold(client):
    state=window.inspect(client);assert not state['running'] and state['started_at']==EXPECTED and state['exit_code']==0 and state['error_free'] and not state['oom_killed'] and not state['restarting'] and not state['paused'] and state['restart_policy']['Name']=='no','exact cold exit0 required';return state
def players0(client,state):
    access=window.load_access()
    with client.open_sftp() as sftp:
        with sftp.file(window.DEV_ROOT+'/server.properties','r') as f:lines=f.read().decode().splitlines()
    props=dict(line.split('=',1) for line in lines if '=' in line and not line.lstrip().startswith('#'))
    channel=client.get_transport().open_channel('direct-tcpip',(state['bridge_ip'],int(props.get('rcon.port','25575'))),('127.0.0.1',0));channel.settimeout(15)
    try:
        session=window.RconSession(channel,access.unescape_properties(props.get('rcon.password','')));assert session.command('execute if entity @a').strip()=='Test failed','native players0 not proved'
    finally:channel.close()
def sha_sftp(sftp,path):
    d=hashlib.sha256()
    with sftp.file(path,'rb') as f:
        for b in iter(lambda:f.read(1048576),b''):d.update(b)
    return d.hexdigest()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('phase',choices=['preflight','stop','stage','start']);args=parser.parse_args();assert hashlib.sha256(SOURCE.read_bytes()).hexdigest()==NEW,'immutable source hash mismatch'
    if args.phase=='stop':
        pre=read('preflight');assert pre['state']['started_at']==EXPECTED and pre['players0'] and pre['appSha256']==OLD
        result=subprocess.run([sys.executable,str(WINDOW),'stop','--expected-started-at',EXPECTED,'--release-evidence',RELEASE],capture_output=True,text=True,encoding='utf-8');proof=json.loads(result.stdout);write('stop',proof);assert result.returncode==0 and proof['result']=='COLD_EXIT_0','stop proof failed; no file mutation allowed';print(json.dumps(proof));return
    access=window.load_access();client=access.connect()
    try:
        if args.phase=='preflight':
            state=window.inspect(client);window.admit_stop(state,EXPECTED);assert remote(client,'docker exec '+window.DEV_CONTAINER+' id -u')=='0','runtime UID0 required';players0(client,state);current=window.inspect(client);assert all(current[k]==state[k] for k in ['started_at','host_pid','bridge_ip'])
            snap=snapshot(client);verify_config(snap);assert snap['mods/JustTeams-2.5.5+pxc.1.jar']['sha256']==OLD
            proof={'state':state,'players0':True,'runtimeUid':0,'appSha256':OLD,'snapshot':snap,'releaseEvidence':RELEASE,'delegation':'Root explicit app-only cold swap after graceful client quit; no other writer'};write('preflight',proof);print(json.dumps({'phase':'preflight','exactBoot':EXPECTED,'players0':True,'app686Verified':True,'privateSixVerified':True,'runtimeUid':0,'protectedFiles':len(snap)}));return
        if args.phase=='stage':
            stop=read('stop');assert stop['result']=='COLD_EXIT_0' and stop['released_boot']==EXPECTED and stop['release_evidence']==RELEASE
            state=cold(client);before=snapshot(client,True);verify_config(before);assert before['mods/JustTeams-2.5.5+pxc.1.jar']['sha256']==OLD
            pre=read('preflight')['snapshot'];assert all(before[k]==v for k,v in pre.items()),'static files changed since preflight; keep cold'
            archive=KIT/'app686-offserver-before-7dd.jar';assert not archive.exists(),'offserver archive already exists'
            incoming=window.DEV_ROOT+'/.incoming-7dd-'+str(time.time_ns())+'.jar'
            with client.open_sftp() as sftp:
                sftp.get(APP,str(archive));assert hashlib.sha256(archive.read_bytes()).hexdigest()==OLD,'archive hash mismatch';cold(client)
                with sftp.file(incoming,'wx') as f:
                    with SOURCE.open('rb') as src:
                        for b in iter(lambda:src.read(1048576),b''):f.write(b)
                    f.flush()
                assert sha_sftp(sftp,incoming)==NEW,'incoming hash mismatch';cold(client)
                mode=before['mods/JustTeams-2.5.5+pxc.1.jar']['mode'];sftp.chmod(incoming,mode);sftp.chown(incoming,0,before['mods/JustTeams-2.5.5+pxc.1.jar']['gid']);sftp.remove(APP);sftp.rename(incoming,APP)
                assert sha_sftp(sftp,APP)==NEW and sftp.stat(APP).st_uid==0,'installed hash/UID mismatch'
            after=snapshot(client,True);verify_config(after);protected={k:v for k,v in before.items() if k!='mods/JustTeams-2.5.5+pxc.1.jar'};assert all(after[k]==v for k,v in protected.items()),'protected state changed; keep cold';cold(client)
            proof={'state':state,'appSha256':NEW,'archiveOffserver':str(archive),'protectedFilesUnchanged':True,'protected':protected,'installed':after['mods/JustTeams-2.5.5+pxc.1.jar'],'privateSixVerified':True,'runtimeUid':0};write('stage',proof);print(json.dumps({'phase':'stage','appSha256':NEW,'protectedFilesUnchanged':len(protected),'archiveOffserver':str(archive),'privateSixVerified':True,'stillCold':True}));return
        stage=read('stage');assert stage['appSha256']==NEW and stage['protectedFilesUnchanged'];cold(client);before=snapshot(client,True);assert before['mods/JustTeams-2.5.5+pxc.1.jar']['sha256']==NEW and all(before[k]==v for k,v in stage['protected'].items());verify_config(before)
        remote(client,'docker start '+window.DEV_CONTAINER);state=window.inspect(client);assert state['running'] and state['started_at']!=EXPECTED and state['restart_policy']['Name']=='no';write('start',state);print(json.dumps(state))
    finally:client.close()

if __name__=='__main__':
    try:main()
    except Exception as e:
        print(json.dumps({'result':'ABORT_NO_UNCONDITIONAL_CONTINUE','failureType':type(e).__name__,'reason':str(e) if isinstance(e,AssertionError) else 'phase failed; preserve current cold/state and report'}));raise SystemExit(2)
