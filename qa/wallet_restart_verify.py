"""Readback/replay check after cold restart of the full wallet fault fixture."""
import json
from rig import ROOT
from run_cases import qa, state, balance, wallet

record=json.loads((ROOT/'qa-wallet-fault.json').read_text(encoding='utf-8'))
assert record.get('sameIntentNoReplay') is True
before=state('wallet-fault-cold-restart-before')
assert 'pending=1' in before['status'] and 'phase=FROZEN' in before['pending']
assert balance(before)==record['bankBefore'] and wallet(before)==record['walletBefore']
after=qa('bank JTMoneyOwner deposit 1 '+record.get('opid','jtqa_walletfault_0001'))
assert balance(after['result'])==balance(before) and wallet(after['result'])==wallet(before)
assert 'phase=FROZEN' in after['result']['pending']
outcome={'rung':'state','n':1,'interval':'one cold restart','bankUnchanged':True,
  'walletReloadedExactPersistedBefore':True,'frozenIntentNoReplay':True,'artifact':after['artifact']}
(ROOT/'qa-wallet-restart.json').write_text(json.dumps(outcome,indent=2),encoding='utf-8')
print(json.dumps(outcome))
