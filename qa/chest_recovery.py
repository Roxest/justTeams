"""FROZEN chest recovery matrix on the marked own rig (Carpet actors, pxc-auth held out, state rung).

Per case: `fault <case>` (rig booted fresh) -> stop -> boot -> `recover <case>` (actor offline) -> `rejoin <case>`.
Cases: pickup (chest->cursor), place (cursor->chest), quick (shift chest->player).
The marked stack must exist exactly once across stored chest + actor inventory + claimable escrow after recovery.
"""
import argparse
import json
from pathlib import Path
import re
import time
from rig import ROOT, send
from run_cases import qa, state, actor, click, spawn_actors

PROOF = ROOT / 'qa-chest-recovery.json'
WAL = ROOT / 'config/JustTeams/chest-intents.jsonl'
report = json.loads(PROOF.read_text(encoding='utf-8')) if PROOF.exists() else {}
MARK = {'pickup': 'recA', 'place': 'recB', 'quick': 'recC'}


def save(key, value):
    report[key] = {'utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'rung': 'state', **value}
    PROOF.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({key: report[key]}), flush=True)


def await_slots(name, expected, label):
    deadline = time.monotonic() + 5
    while True:
        snap = state(label)
        if actor(snap, name)['slots'] == expected or time.monotonic() > deadline:
            return snap
        time.sleep(.25)


def snbt_count(text, mark):
    """Marked items in an inventory SNBT list: one element per `{Slot:` stack."""
    return sum(int(m.group(1)) for part in text.split('{Slot:')[1:] if mark in part for m in [re.search(r'count:(\d+)', part)] if m)


def chest_count(inventory_json, mark):
    """Marked items in a NativeChestCodec envelope (slot item JSON carries count)."""
    if not inventory_json:
        return 0
    return sum(slot['item']['count'] for slot in json.loads(inventory_json)['slots'] if slot and mark in json.dumps(slot))


def stored_count(snapshot, mark):
    return chest_count(snapshot['chest']['inventoryJson'], mark) if snapshot.get('chest') else 0


def fault(case):
    mark = MARK[case]
    spawn_actors()
    qa('close JTMoneyOwner')
    send('clear JTMoneyOwner minecraft:diamond'); time.sleep(.3)
    item = 'minecraft:diamond[minecraft:custom_data={fixture:"%s"}] 5' % mark
    if case in ('pickup', 'quick'):
        # Put the marked stack into the chest normally first (committed), then fault on taking it out.
        send('item replace entity JTMoneyOwner hotbar.4 with ' + item); time.sleep(.4)
        qa('core ec JTMoneyOwner'); opened = await_slots('JTMoneyOwner', 90, case + '-open')
        top = actor(opened, 'JTMoneyOwner')['slots'] - 36
        click('JTMoneyOwner', top + 27 + 4); click('JTMoneyOwner', 0)
        qa('close JTMoneyOwner')
        qa('core ec JTMoneyOwner'); await_slots('JTMoneyOwner', 90, case + '-reopen')
        before = state(case + '-before')
        assert stored_count(before, mark) == 5, 'setup: marked stack not stored'
        qa('armsql')
        fault_row = click('JTMoneyOwner', 0, 'QUICK_MOVE' if case == 'quick' else 'PICKUP')['result']
    else:
        send('item replace entity JTMoneyOwner hotbar.4 with ' + item); time.sleep(.4)
        qa('core ec JTMoneyOwner'); opened = await_slots('JTMoneyOwner', 90, case + '-open')
        top = actor(opened, 'JTMoneyOwner')['slots'] - 36
        click('JTMoneyOwner', top + 27 + 4)  # committed pickup into cursor
        before = state(case + '-before')
        assert 'diamond' in actor(before, 'JTMoneyOwner')['cursor'], actor(before, 'JTMoneyOwner')['cursor']
        empty = next(i for i, s in enumerate(json.loads(before['chest']['inventoryJson'])['slots']) if s is None)
        qa('armsql')
        fault_row = click('JTMoneyOwner', empty)['result']
    q = {row['name']: row for row in fault_row['quarantine']}
    assert q['JTMoneyOwner']['actorQuarantined'] is True
    rows = [json.loads(l) for l in WAL.read_text(encoding='utf-8').splitlines() if l]
    frozen = next(r for r in reversed(rows) if r['phase'] == 'FROZEN')
    inv = frozen['payload']['quarantinedInventory']; cur = frozen['payload'].get('cursorEscrow', '')
    save('fault-' + case, {'opid': frozen['opid'], 'storedMarkedBefore': stored_count(before, mark),
                           'quarantinedInventoryMarked': snbt_count(inv, mark), 'cursorEscrowMarked': chest_count(cur, mark),
                           'generationBefore': before['chest']['generation']})


def console(command, pattern):
    log = Path((ROOT / 'qa-current-log.txt').read_text(encoding='utf-8'))
    start = len(log.read_text(encoding='utf-8', errors='replace'))
    send(command)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        tail = log.read_text(encoding='utf-8', errors='replace')[start:]
        m = re.search(pattern, tail)
        if m:
            return m.group(0)
        time.sleep(.3)
    raise TimeoutError(command)


def recover(case):
    opid = report['fault-' + case]['opid']
    qa('seedprofiles')
    for name in ('JTMoneyMember', 'JTMoneyCo'):
        send(f'player {name} spawn at 1 100 0')
    time.sleep(4)
    snap = state('recover-before-' + case)
    assert not any(a['name'] == 'JTMoneyOwner' for a in snap['actors']), 'actor must be offline'
    dry = console(f't admin chestrecover {opid}', r'(DRY-RUN op=' + opid + r'[^\n]*|Refusal[^\n]*|RECOVERY_[A-Z_]+)')
    applied = console(f't admin chestrecover {opid} apply', r'(APPLIED op=' + opid + r'[^\n]*|RECOVERY_[A-Z_]+)')
    after = state('recover-after-' + case)
    save('recover-' + case, {'dryRun': dry, 'applied': applied, 'status': after['status'], 'storedMarked': stored_count(after, MARK[case]),
                             'generation': after['chest']['generation']})
    assert applied.startswith('APPLIED'), applied


def rejoin(case):
    mark = MARK[case]
    send('player JTMoneyOwner spawn at 0 100 0')
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        snap = state('rejoin-' + case)
        if any(a['name'] == 'JTMoneyOwner' for a in snap['actors']):
            break
        time.sleep(.4)
    else:
        raise AssertionError('recovered actor was not admitted')
    time.sleep(1.5)
    snap = state('rejoin-settled-' + case)
    owner = actor(snap, 'JTMoneyOwner')
    claimed = None
    if 'phase=ESCROW' in report['recover-' + case]['applied']:
        claimed = qa('core claim JTMoneyOwner')
        snap = state('claimed-' + case); owner = actor(snap, 'JTMoneyOwner')
    inventory, stored = snbt_count(owner['inventory'], mark), stored_count(snap, mark)
    qa('core ec JTMoneyMember')
    member_open = await_slots('JTMoneyMember', 90, 'member-ec-' + case)
    member_ok = actor(member_open, 'JTMoneyMember')['slots'] == 90
    qa('close JTMoneyMember')
    total = inventory + stored
    save('rejoin-' + case, {'admitted': True, 'inventoryMarked': inventory, 'storedMarked': stored, 'totalMarked': total,
                            'claimed': bool(claimed), 'memberChestOpens': member_ok, 'status': state('final-' + case)['status']})
    assert total == 5, f'marked stack must exist exactly once (5 items), got {total}'
    assert member_ok


parser = argparse.ArgumentParser()
parser.add_argument('stage', choices=('fault', 'recover', 'rejoin'))
parser.add_argument('case', choices=tuple(MARK))
a = parser.parse_args()
{'fault': fault, 'recover': recover, 'rejoin': rejoin}[a.stage](a.case)
