"""Read-only exact PxcEyes2 identity/status/inventory hash; never auth commands or raw inventory output."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import uuid
from datetime import datetime,timezone
parser=argparse.ArgumentParser();parser.add_argument('label');args=parser.parse_args();assert re.fullmatch('[a-z0-9_-]+',args.label)
def query(command):
    result=subprocess.run([sys.executable,'F:/Pixelclub/tools/pxc.py','dev','rcon',command],capture_output=True,text=True,encoding='utf-8');assert result.returncode==0,'read-only RCON failed';return re.sub(r'\u00a7.','',result.stdout).strip()
identity=query('minecraft:data get entity PxcEyes2 UUID');match=re.search(r'\[I;\s*([-\d]+),\s*([-\d]+),\s*([-\d]+),\s*([-\d]+)\]',identity);uid=str(uuid.UUID(bytes=struct.pack('>iiii',*(int(n) for n in match.groups())))) if match else None
status=query('t admin status');match=re.search(r'ready=\S+ snapshot-health=\S+ teams=\d+ members=\d+ pending=\d+ chests=\d+ provider=\S+',status);safe_status=match.group() if match else 'unavailable'
inventory=query('minecraft:data get entity PxcEyes2 Inventory');prefix='PxcEyes2 has the following entity data: ';present=inventory.startswith(prefix);snbt=inventory[len(prefix):] if present else ''
proof={'localUTC':datetime.now(timezone.utc).isoformat(),'rung':'state','readOnly':True,'identity':'PxcEyes2','serverUUID':uid,'canaryUUIDMatches':uid=='42bb1850-a13a-35e8-aa40-c9d2bb34a1ae','status':safe_status,'inventoryAvailable':present,'inventorySha256':hashlib.sha256(snbt.encode()).hexdigest() if present else None,'inventorySlotTags':len(re.findall(r'(?:^|[,{ ])Slot:',snbt)) if present else None,'cursorServerField':'not exposed; requires client seen evidence','authMeBoolRPC':'not exposed; C3 inferred only from actual native GUI authorization'}
target=Path('F:/Pixelclub/scratch/justteams-cutover-20261002/private-dev-canary-20261003')/('swap7dd-actor-'+args.label+'.json');target.write_text(json.dumps(proof,indent=2),encoding='utf-8');print(json.dumps(proof))
