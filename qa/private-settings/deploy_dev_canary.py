"""Root-owned released DEV canary phases; never targets production or prints access secrets."""
import contextlib, hashlib, importlib.util, io, json, os, re, shlex, sys, time
from pathlib import Path
from datetime import datetime, timezone

KIT=Path('F:/Pixelclub/scratch/justteams-cutover-20261002/private-dev-canary-20261003')
ROOT='/root/pixelclub/dev-pixelclub-server'
CONTAINER='pixelclub-arclight-dev'
STARTED='2026-10-03T01:52:09.51251204Z'
OLD='mods/justTeams-2.5.5.jar'
NEW='mods/JustTeams-2.5.5+pxc.1.jar'
DB='config/JustTeams/data/teams-pxc.mv.db'
FILES=('config.yml','gui.yml','messages.yml','commands.yml','placeholders.yml','quests.yml')
OLD_SHA='002a0e76b9695f8f029cdd4b080c68f8cf2fac7e7476b122524b406742347323'
NEW_SHA='6864630f29a2b33cc0299e3cfcbca4335a1916e459c8208b8460165835567487'
DB_SHA='2e79a569fb5e09f4b660819bbcfac65cd2c2ee70c315259319ed9d5aef06ff28'

class Refused(RuntimeError): pass
def digest(data): return hashlib.sha256(data).hexdigest()
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path); value=importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
def command(client,text):
    _,out,err=client.exec_command(text,timeout=15); data=out.read(); err.read()
    if out.channel.recv_exit_status()!=0: raise Refused('remote_phase_failed')
    return data.decode().strip()
def remote(sftp,relative):
    if relative.startswith('/') or '..' in relative.split('/'): raise Refused('path_refused')
    return ROOT+'/'+relative
def read(sftp,relative):
    path=remote(sftp,relative); before=sftp.stat(path)
    with sftp.open(path,'rb') as stream: data=stream.read()
    after=sftp.stat(path)
    if (before.st_size,before.st_mtime)!=(after.st_size,after.st_mtime): raise Refused('file_changed')
    return data
def missing(sftp,relative):
    try: sftp.stat(remote(sftp,relative)); return False
    except FileNotFoundError: return True
    except OSError as error:
        if error.errno==2: return True
        raise
def journal(name,value):
    path=KIT/(name+'.json')
    with path.open('x',encoding='utf-8') as stream: json.dump(value,stream,separators=(',',':'))
def cold(window,client,started=STARTED):
    state=window.inspect(client)
    if state['started_at']!=started or state['running'] or state['host_pid']!=0 or state['restarting'] or state['paused'] or state['exit_code']!=0 or state['oom_killed'] or not state['error_free'] or state['restart_policy']['Name']!='no': raise Refused('not_exact_clean_cold_dev')
    return state
def baseline(sftp):
    expected=json.loads((KIT/'preflight-private.json').read_text())['mods_metadata']
    actual=[{'filename':value.filename,'bytes':value.st_size,'modified_utc':datetime.fromtimestamp(value.st_mtime,timezone.utc).isoformat()} for value in sftp.listdir_attr(ROOT+'/mods') if value.filename.endswith('.jar')]
    if sorted(actual,key=lambda value:value['filename'])!=sorted(expected,key=lambda value:value['filename']): raise Refused('mod_roster_or_mtimes_changed')
    if digest(read(sftp,OLD))!=OLD_SHA or digest((KIT/'rollback'/OLD).read_bytes())!=OLD_SHA: raise Refused('baseline_jar_changed')
    if not missing(sftp,NEW) or not missing(sftp,DB): raise Refused('new_targets_already_exist')
    for name in FILES:
        relative='config/JustTeams/'+name
        if digest(read(sftp,relative))!=digest((KIT/'rollback'/relative).read_bytes()): raise Refused('baseline_config_changed')
    for relative,expected in {'mods/pxctools-1.0.0.jar':'a7092a77b760e5b6f40467879d952d69f8ab889b296726f3c73f5e726c5d725c','plugins/DonutTools-3.0.jar':'565a0b0443eff690a46a1db296e3aaac182d0909e8ccb9947bf7f91516fa3db5','mods/PxcAC-Server-1.18.9.jar':'64516462774cd1c860176c01e4668f3ec7e8d96f4f75052df883e6ff6108f2fb'}.items():
        if digest(read(sftp,relative))!=expected: raise Refused('peer_baseline_changed')
def jvm_identity(client,pid):
    # Read process name/UID/GID only, never argv or environment.
    program="import pathlib,json; root=int("+str(pid)+"); todo=[root]; found=[]\nwhile todo:\n p=todo.pop(); d=pathlib.Path('/proc')/str(p)\n try:\n  name=(d/'comm').read_text().strip(); status=(d/'status').read_text().splitlines(); children=(d/'task'/str(p)/'children').read_text().split(); todo.extend(map(int,children))\n  if name=='java':\n   vals={l.split(':',1)[0]:l.split(':',1)[1].split() for l in status if l.startswith(('Uid:','Gid:'))}; found.append({'uid':int(vals['Uid'][1]),'gid':int(vals['Gid'][1])})\n except (FileNotFoundError,ProcessLookupError): pass\nprint(json.dumps(found))"
    values=json.loads(command(client,'python3 -c '+shlex.quote(program)))
    if len(values)!=1: raise Refused('java_identity_not_unique')
    return values[0]
def main(action):
    with contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
        access=module('canary_access','F:/Pixelclub/tools/pxc.py')
        window=module('canary_window','F:/Pixelclub/pxctools-fabric/port/runtime/dev_window.py')
        client=access.connect()
    try:
        state=window.inspect(client)
        if action in ('inspect','pin'):
            if state['started_at']!=STARTED or state['host_pid']!=3724316 or not state['running'] or state['paused'] or state['restarting']: raise Refused('released_boot_changed')
            with client.open_sftp() as sftp: baseline(sftp)
            players=access.rcon(client,ROOT,'execute if entity @a',CONTAINER)
            if players.strip()!='Test failed': raise Refused('players_not_zero_or_query_unproven')
            identity=jvm_identity(client,state['host_pid'])
            report={'state':state,'jvm':identity,'players_zero':True,'baseline_verified':True,'action':action}
            if action=='pin':
                if state['restart_policy']['Name']!='unless-stopped': raise Refused('unexpected_prior_policy')
                command(client,'docker update --restart=no '+CONTAINER)
                after=window.inspect(client)
                if after['started_at']!=STARTED or after['host_pid']!=state['host_pid'] or after['restart_policy']['Name']!='no': raise Refused('policy_pin_unproved')
                report['after']=after; journal('root-policy-pin',report)
            else: journal('root-preflight',report)
            print(json.dumps(report,separators=(',',':'))); return
        if action=='stage':
            cold(window,client)
            pin=json.loads((KIT/'root-policy-pin.json').read_text()); identity=pin['jvm']
            with client.open_sftp() as sftp:
                baseline(sftp)
                if digest((KIT/'deploy'/NEW).read_bytes())!=NEW_SHA or digest((KIT/'deploy'/DB).read_bytes())!=DB_SHA: raise Refused('local_deploy_drift')
                directory=remote(sftp,'config/JustTeams/data')
                try: sftp.stat(directory)
                except OSError as error:
                    if error.errno!=2: raise
                    sftp.mkdir(directory,0o750); sftp.chown(directory,identity['uid'],identity['gid'])
                changed=[]
                for relative in ['config/JustTeams/'+name for name in FILES]+[DB]:
                    cold(window,client)
                    data=(KIT/'deploy'/relative).read_bytes(); target=remote(sftp,relative)
                    temporary=target+'.root-canary-incoming'
                    with sftp.open(temporary,'wxb') as stream: stream.write(data); stream.flush()
                    sftp.chown(temporary,identity['uid'],identity['gid']); sftp.chmod(temporary,0o600)
                    if digest(read(sftp,relative+'.root-canary-incoming'))!=digest(data): raise Refused('uploaded_hash_mismatch')
                    sftp.posix_rename(temporary,target); changed.append(relative)
                    journal('root-stage-'+str(len(changed)),{'changed':changed,'hash':digest(data),'cold':cold(window,client)})
                cold(window,client)
                if digest(read(sftp,OLD))!=OLD_SHA: raise Refused('old_jar_changed_before_remove')
                sftp.remove(remote(sftp,OLD))
                journal('root-old-jar-removed',{'rollback_offserver_sha':OLD_SHA,'cold':cold(window,client)})
                with sftp.open(remote(sftp,NEW),'wxb') as stream: stream.write((KIT/'deploy'/NEW).read_bytes()); stream.flush()
                sftp.chown(remote(sftp,NEW),identity['uid'],identity['gid']); sftp.chmod(remote(sftp,NEW),0o644)
                if digest(read(sftp,NEW))!=NEW_SHA or not missing(sftp,OLD): raise Refused('app_placement_unproved')
                report={'app_sha256':NEW_SHA,'database_initial_sha256':digest(read(sftp,DB)),'configs':{name:digest(read(sftp,'config/JustTeams/'+name)) for name in FILES},'jvm':identity,'cold':cold(window,client),'no_prd_mutations':True}
                journal('root-stage-complete',report); print(json.dumps(report,separators=(',',':'))); return
        if action=='start':
            cold(window,client)
            proof=json.loads((KIT/'root-stage-complete.json').read_text())
            with client.open_sftp() as sftp:
                if digest(read(sftp,NEW))!=NEW_SHA or not missing(sftp,OLD) or digest(read(sftp,DB))!=DB_SHA: raise Refused('staged_app_data_drift')
                for name,expected in proof['configs'].items():
                    if digest(read(sftp,'config/JustTeams/'+name))!=expected: raise Refused('staged_config_drift')
            command(client,'docker start '+CONTAINER)
            after=window.inspect(client)
            if not after['running'] or after['started_at']==STARTED or after['restart_policy']['Name']!='no': raise Refused('new_boot_not_proved')
            journal('root-canary-start',after); print(json.dumps(after,separators=(',',':'))); return
        if action=='status':
            print(json.dumps(state,separators=(',',':')))
            if state['running']:
                print(access.rcon(client,ROOT,'team admin status',CONTAINER))
                print(access.rcon(client,ROOT,'list',CONTAINER))
            return
        raise Refused('unknown_action')
    finally: client.close()
if __name__=='__main__':
    try: main(sys.argv[1])
    except Exception as error:
        # Credentials or remote diagnostics never enter stdout/stderr.
        print(json.dumps({'refused_type':type(error).__name__,'reason':str(error) if isinstance(error,Refused) else 'diagnostics_redacted','action':sys.argv[1]})); raise SystemExit(2)
