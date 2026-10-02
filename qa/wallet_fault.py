"""Fault the one explicitly synthetic owner receipt; backup lives outside the rig."""
import json
import argparse
from pathlib import Path
import time
from rig import ROOT, marked
from run_cases import qa, state, actor, wallet, balance

parser=argparse.ArgumentParser();parser.add_argument('mode',choices=('full','provider-only'));args=parser.parse_args()
marked()
before=state('wallet-fault-before')
if args.mode=='full':assert 'pending=0' in before['status'], 'Full bank fault requires an unfrozen clean fixture'
owner=actor(before,'JTMoneyOwner')
uuid=owner['uuid']
assert uuid=='a5eb7d24-72b6-3d8f-87f4-b88e663f22eb'
fence=qa('fence JTMoneyOwner 0')['result'];assert fence['quiet'] is True
checkpoint=fence['sequence']
receipt=(ROOT/'config/impactor/economy/accounts/users'/uuid[:2]/(uuid+'.conf')).resolve()
assert receipt.is_relative_to(ROOT.resolve()) and receipt.is_file() and not receipt.is_symlink()
outside=Path('F:/Pixelclub/scratch/justteams-runtime-walletfault-20261003')/str(time.time_ns())
outside.mkdir(parents=True);backup=outside/receipt.name
receipt.rename(backup);receipt.mkdir()
outcome={'checkpoint':checkpoint,'backupOutsideRig':str(backup),'bankBefore':balance(before),'walletBefore':wallet(before)}
try:
    try:qa(f'wallet JTMoneyOwner {wallet(before):.2f}')
    except RuntimeError as error:outcome['explicitFutureException']=str(error)
    else:raise AssertionError('Real provider save future swallowed filesystem failure')
    fenced=qa(f'fence JTMoneyOwner {checkpoint}')
    assert fenced['result']['quiet'] is True and fenced['result']['awaitSince'] is False
    outcome['implicitFutureFence']=fenced
    if args.mode=='full':
        transfer=qa('bank JTMoneyOwner deposit 1 jtqa_walletfault_0001')
        after=transfer['result'];assert balance(after)==balance(before)
        assert 'phase=FROZEN' in after['pending'] and 'pending=1' in after['status']
        outcome['pendingArtifact']=transfer['artifact'];outcome['walletInMemoryAfter']=wallet(after)
finally:
    assert receipt.is_dir() and not list(receipt.iterdir()), 'Refuse nonempty receipt obstruction cleanup'
    receipt.rmdir();backup.rename(receipt)
after=state('wallet-fault-restored-file')
if args.mode=='full':
    replayed=qa('bank JTMoneyOwner deposit 1 jtqa_walletfault_0001')['result']
    assert balance(replayed)==balance(before) and wallet(replayed)==wallet(after)
    assert 'phase=FROZEN' in replayed['pending']
    outcome['sameIntentNoReplay']=True
(ROOT/'qa-wallet-fault.json').write_text(json.dumps(outcome,indent=2),encoding='utf-8')
print(json.dumps({'rung':'state','explicitExceptionPropagated':True,'implicitFuturesFenced':True,'bankFrozenNoReplay':args.mode=='full','artifact':str(ROOT/'qa-wallet-fault.json')}))
