"""Cold staging of the final pinned artifact with fresh public YAML and synthetic data."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import zipfile
import yaml
from rig import ROOT,marked

marked()
artifact=Path('F:/Pixelclub/scratch/justteams-cutover-20261002/artifacts/JustTeams-2.5.5+pxc.1-b613906a.jar')
assert hashlib.sha256(artifact.read_bytes()).hexdigest()=='b613906a0210ac5f39203ab956606e56fb9eb5296fe61d016ffcf1c75ecfcdd9'
subprocess.run([__import__('sys').executable,str(Path(__file__).with_name('reset_seed.py')),'--accounts'],check=True)
shutil.copy2(artifact,ROOT/'mods/JustTeams-2.5.5+pxc.1.jar')
with zipfile.ZipFile(artifact) as archive:
    for name in ('config.yml','messages.yml','gui.yml','quests.yml','placeholders.yml','commands.yml'):
        (ROOT/'config/JustTeams'/name).write_bytes(archive.read(name))
configPath=ROOT/'config/JustTeams/config.yml'
config=yaml.safe_load(configPath.read_text(encoding='utf-8'))
config['storage']={'type':'h2','table_prefix':'donut_'}
config['server-identifier']='fixture-local'
config.setdefault('team_bank',{}).update({'enabled':True,'max_balance':1000000000})
for key in ('team_home','team_warps'):config.setdefault(key,{}).update({'warmup_seconds':5,'cooldown_seconds':5})
config.setdefault('feature_costs',{}).setdefault('economy',{}).update({'bank_deposit':0,'bank_withdraw':0,'sethome':250000,'setwarp':500000,'rename':50000})
configPath.write_text(yaml.safe_dump(config,sort_keys=False,allow_unicode=True),encoding='utf-8')
print('Pinned candidate/public configs staged; exact FixtureBootstrap required next')
