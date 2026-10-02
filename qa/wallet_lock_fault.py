"""Keep receipt readable/present while Windows sharing blocks real provider writes."""
import ctypes
from ctypes import wintypes
import json
from rig import ROOT, marked
from run_cases import qa, state, wallet, balance, actor

marked()
before=state('wallet-sharing-fault-before')
owner=actor(before,'JTMoneyOwner');uuid=owner['uuid']
assert uuid=='a5eb7d24-72b6-3d8f-87f4-b88e663f22eb'
assert 'pending=0' in before['status']
# Resolve the actual cached provider account normally after the directory-obstruction control.
qa(f'wallet JTMoneyOwner {wallet(before):.2f}')
before=state('wallet-sharing-loaded-before')
checkpoint=qa('fence JTMoneyOwner 0')['result']['sequence']
receipt=ROOT/'config/impactor/economy/accounts/users'/uuid[:2]/(uuid+'.conf')
assert receipt.is_file() and not receipt.is_symlink()
kernel=ctypes.WinDLL('kernel32',use_last_error=True)
kernel.CreateFileW.argtypes=[wintypes.LPCWSTR,wintypes.DWORD,wintypes.DWORD,ctypes.c_void_p,wintypes.DWORD,wintypes.DWORD,wintypes.HANDLE]
kernel.CreateFileW.restype=wintypes.HANDLE
kernel.CloseHandle.argtypes=[wintypes.HANDLE]
handle=kernel.CreateFileW(str(receipt),0x80000000,0x00000001,None,3,0x80,None)
if handle==wintypes.HANDLE(-1).value:raise ctypes.WinError(ctypes.get_last_error())
try:
    transfer=qa('bank JTMoneyOwner deposit 1 jtqa_walletlock_0001')
    after=transfer['result']
    assert balance(after)==balance(before), 'SQL bank must not move after unknown player persistence'
    assert 'phase=FROZEN' in after['pending'] and 'pending=1' in after['status'], 'Unknown write must be quarantined'
    fenced=qa(f'fence JTMoneyOwner {checkpoint}')
    assert fenced['result']['quiet'] is True and fenced['result']['awaitSince'] is False
finally:
    kernel.CloseHandle(handle)
restored=state('wallet-sharing-lock-released')
replayed=qa('bank JTMoneyOwner deposit 1 jtqa_walletlock_0001')['result']
assert balance(replayed)==balance(before) and wallet(replayed)==wallet(restored)
outcome={'rung':'state','n':1,'interval':'per operation','opid':'jtqa_walletlock_0001',
  'bankBefore':balance(before),'walletBefore':wallet(before),'walletInMemoryAfter':wallet(restored),
  'implicitFutureFence':fenced,'pendingArtifact':transfer['artifact'],'sameIntentNoReplay':True,
  'fault':'read-sharing handle blocks write/delete; receipt remains present/readable'}
(ROOT/'qa-wallet-fault.json').write_text(json.dumps(outcome,indent=2),encoding='utf-8')
print(json.dumps({'rung':'state','bankFrozenNoReplay':True,'implicitFutureFailure':True,'artifact':str(ROOT/'qa-wallet-fault.json')}))
