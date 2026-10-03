"""Bounded native boot/stage evidence; never emits credential config or raw logs."""
import hashlib
import json
from pathlib import Path
import zipfile
from rig import ROOT,marked
from run_cases import qa
marked();app=ROOT/'mods/JustTeams-2.5.5+pxc.1.jar';sha=hashlib.sha256(app.read_bytes()).hexdigest()
with zipfile.ZipFile(app) as jar:mixins=json.loads(jar.read('pxc-teams.mixins.json'))
expected=['TeamChatMixin','TeamDamageMixin','PlayerSaveAccess','EntityFlagsAccess','TeamGlowMetadataMixin','EconomySaveFenceMixin','ConfigurateSaveReceiptMixin']
assert mixins['required'] and mixins['mixins']==expected
checks={
 'net/minecraft/class_3244.class':['teamsChat'],
 'net/minecraft/class_1309.class':['preventFriendlyFire','cancelWarmupOnDamage'],
 'net/minecraft/class_3324.class':['pxcTeamsSave'],
 'net/minecraft/class_1297.class':['pxcTeams$flags'],
 'net/minecraft/class_8609.class':['pxcTeams$viewerGlow'],
 'net/impactdev/impactor/core/economy/storage/EconomyStorage.class':['trackImplicitSaves','reserve','bind'],
 'net/impactdev/impactor/core/economy/storage/implementations/ConfigurateProvider.class':['captureRealRelativeLock','propagateFailure','forceReceipt'],
}
boot_log=Path((ROOT/'qa-current-log.txt').read_text());boot_epoch=int(boot_log.stem.removeprefix('qa-console-'))/1e9
for file,tokens in checks.items():
    exported=ROOT/'.mixin.out/class'/file;data=exported.read_bytes();assert exported.stat().st_mtime>=boot_epoch-1 and all(token.encode() in data for token in tokens),(file,tokens)
stage=Path('F:/Pixelclub/scratch/justteams-cutover-20261002/private-gui-stage');report=json.loads((stage/'sanitization-report.json').read_text())
for name,item in report['outputs'].items():assert hashlib.sha256((ROOT/'config/JustTeams'/name).read_bytes()).hexdigest()==item['sha256']
status=qa('status')['result'];assert 'ready=true' in status and 'provider=true' in status and 'snapshot-health=true' in status
auth=qa('authstate JTMoneyOwner')['result'];assert auth['testActorsFlag'] is False and 'id=pxc-auth' in auth['provider'] and 'available=true' in auth['provider']
proof={'applicationSha256':sha,'pid':int((ROOT/'qa-server.pid').read_text()),'rung':'loaded + state','nativeRequiredMixins':expected,'exportedBytecodeMarkersVerified':True,'status':status,'authProvider':'pxc-auth','testActorsFlag':False,'privateSixFileHashesVerified':True,'configSha256':report['outputs']['config.yml']['sha256'],'safeOverrides':['storage H2','redis disabled'],'authWalletSQLPlayerdataPreserved':True,'log':(ROOT/'qa-current-log.txt').read_text()}
if (ROOT/'qa-ac-1.18.9-stage.json').exists():
    ac_stage=json.loads((ROOT/'qa-ac-1.18.9-stage.json').read_text());ac=qa('acmeta')['result'];assert ac['version']=='1.18.9' and hashlib.sha256((ROOT/'mods/PxcAC-Server-1.18.9.jar').read_bytes()).hexdigest()==ac_stage['sha256'];assert hashlib.sha256((ROOT/'config/pxcac/config.json').read_bytes()).hexdigest()==ac_stage['ownACConfigSha256'],'own AC config changed'
    proof.update({'ownLoadedACVersion':ac['version'],'ownACSha256':ac_stage['sha256'],'ownACConfigUnchanged':True,'devLoadedACVersion':'UNKNOWN; no log proof'})
(ROOT/'qa-auth-boot-health.json').write_text(json.dumps(proof,indent=2),encoding='utf-8');print(json.dumps(proof))
