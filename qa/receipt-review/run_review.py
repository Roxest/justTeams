from pathlib import Path
import hashlib
import subprocess

root = Path('F:/Pixelclub/justteams-fabric-pxc')
out = root / 'qa/receipt-review'
classes = out / 'classes'
classes.mkdir(parents=True, exist_ok=True)
cache = Path('C:/Users/Kraus/.gradle/caches/modules-2/files-2.1')
jdk = Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin')
def jar(group, artifact, version):
    return next((cache/group/artifact/version).rglob(f'{artifact}-{version}.jar'))
jars = [jar('com.google.code.gson','gson','2.11.0'), jar('com.h2database','h2','2.1.214'),
        jar('org.junit.jupiter','junit-jupiter-api','5.11.4'), jar('org.junit.platform','junit-platform-commons','1.11.4'),
        jar('org.apiguardian','apiguardian-api','1.1.2'), jar('org.opentest4j','opentest4j','1.3.0')]
sources = list((root/'src/main/java/com/pixelclub/teams/store').glob('*.java'))
sources += [root/'src/main/java/com/pixelclub/teams/money'/p for p in ['DurableEconomy.java','PackEconomy.java','WalletFence.java','BankService.java']]
sources += [root/'src/test/java/com/pixelclub/teams/money/BankServiceTest.java', out/'BankRunner.java', out/'WalletFenceReview.java', out/'Account.java']
classpath = ';'.join(map(str,jars))
subprocess.run([str(jdk/'javac.exe'),'-cp',classpath,'-d',str(classes),*map(str,sources)],check=True)
results = []
for main in ['com.pixelclub.teams.money.BankRunner','WalletFenceReview']:
    result = subprocess.run([str(jdk/'java.exe'),'-cp',f'{classes};{classpath}',main,str(out/'fixtures')],capture_output=True,text=True)
    results.append(result.stdout + result.stderr)
    if result.returncode:
        print(result.stdout + result.stderr)
        raise SystemExit(result.returncode)
(out/'test-output.txt').write_text('\n'.join(results), encoding='utf-8')
(out/'source-sha256.txt').write_text('\n'.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()} {p.relative_to(root)}' for p in sources[:11]),encoding='utf-8')
print('\n'.join(results))
