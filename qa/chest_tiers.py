"""Paid tier chest 36->45->54, config shrink and SQL loss at 54 on the marked own rig.

State rung only (server handler + H2/WAL readback); Carpet actors need boot (testActors).
Stages run in order with a cold restart between them:
  grow  -> stop -> shrink-config -> boot -> shrink -> restore-config ... -> sqlfault -> stop -> cold -> boot -> relogin
"""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import time
from rig import ROOT, send
from run_cases import qa, state, actor, balance, equal, click, spawn_actors

PROOF = ROOT / 'qa-chest-tiers.json'
CONFIG = ROOT / 'config/JustTeams/config.yml'
DONOR_CONFIG_SHA = 'f7f8d7b908162d0aecf600f17ce51b4f09c2ac9a562375cf8febb99cf21e1a84'
SHRUNK_BACKUP = Path('F:/Pixelclub/scratch/justteams-cutover-20261002/rig-config-before-tiers.yml')
report = json.loads(PROOF.read_text(encoding='utf-8')) if PROOF.exists() else {}


def save(key, value):
    value = {'utc': datetime.now(timezone.utc).isoformat(), 'rung': 'state', **value}
    report[key] = value
    PROOF.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({key: value}), flush=True)


def sha_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stored(snapshot):
    chest = snapshot['chest']
    inventory = json.loads(chest['inventoryJson'])
    return inventory['size'], inventory['slots'], chest['generation']


def team(snapshot):
    return snapshot['teams'][0]


def pending0(snapshot):
    assert 'pending=0' in snapshot['status'], snapshot['status']


def await_slots(name, expected, label):
    """The native screen opens on a later tick than the QA call returns; wait for it."""
    deadline = time.monotonic() + 5
    while True:
        snap = state(label)
        if actor(snap, name)['slots'] == expected or time.monotonic() > deadline:
            return snap
        time.sleep(.25)


def gui_text(name, label):
    """Server-built names/lore of every top slot; asserts no unresolved <token> remains (state, not seen)."""
    row = qa('gui ' + name)
    slots = row['result']['slots'] if isinstance(row['result'], dict) else row['result']
    texts = [t for slot in slots for t in [slot.get('name', '')] + list(slot.get('lore', []))]
    import re
    left = sorted({m for t in texts for m in re.findall(r'<[a-z_]+>', t)})
    save('guiText-' + label, {'unresolved': left, 'n': len(texts), 'artifact': row['artifact'],
                              'sample': [t for t in texts if t.strip()][:24]})
    assert not left, left


def upgrade_via_gui(expected_tier, cost):
    """Owner buys exactly one tier through the actual upgrades and confirm menu handlers."""
    qa('close JTMoneyOwner')
    before = state(f'upgrade{expected_tier}-before')
    qa('core upgrades JTMoneyOwner'); opened = await_slots('JTMoneyOwner', 81, 'upgrade-opened')
    assert actor(opened, 'JTMoneyOwner')['slots'] == 45 + 36, actor(opened, 'JTMoneyOwner')
    gui_text('JTMoneyOwner', f'owner-tier{expected_tier-1}')
    click('JTMoneyOwner', 22)
    confirm = await_slots('JTMoneyOwner', 63, f'upgrade{expected_tier}-confirm')
    assert actor(confirm, 'JTMoneyOwner')['slots'] == 27 + 36, actor(confirm, 'JTMoneyOwner')
    done = click('JTMoneyOwner', 11)['result']
    assert int(team(done)['tier']) == expected_tier, team(done)
    equal(balance(done), balance(before) - cost)
    pending0(done)
    return {'tierBefore': int(team(before)['tier']), 'tierAfter': expected_tier, 'bankBefore': balance(before), 'bankAfter': balance(done), 'artifact': done.get('label')}


def member_locked():
    """Non-owner sees the locked button; clicking it must not buy, and the server refuses direct purchase."""
    qa('close JTMoneyMember')
    before = state('member-upgrade-before')
    qa('core upgrades JTMoneyMember')
    assert actor(await_slots('JTMoneyMember', 81, 'member-upgrade-opened'), 'JTMoneyMember')['slots'] == 45 + 36, 'member must see upgrades GUI'
    gui_text('JTMoneyMember', 'member-locked')
    click('JTMoneyMember', 22)
    after = state('member-upgrade-after')
    assert actor(after, 'JTMoneyMember')['slots'] == 45 + 36, 'locked click must not open confirm'
    assert team(after)['tier'] == team(before)['tier']
    equal(balance(after), balance(before))
    qa('close JTMoneyMember')
    pending0(after)
    return {'tier': int(team(after)['tier']), 'bank': balance(after)}


def move(rows, source, target, label):
    """Open EC, pick stack at `source`, place it at `target`, close. Returns (before, after) stored views."""
    qa('close JTMoneyOwner')
    before = state(label + '-before')
    size0, slots0, gen0 = stored(before)
    qa('core ec JTMoneyOwner'); opened = await_slots('JTMoneyOwner', rows * 9 + 36, label + '-opened')
    top = actor(opened, 'JTMoneyOwner')['slots'] - 36
    assert top == rows * 9, (top, rows)
    assert actor(opened, 'JTMoneyOwner')['menu'].endswith('TeamChests$Menu')
    picked = click('JTMoneyOwner', source)['result']
    assert not actor(picked, 'JTMoneyOwner')['cursor'].startswith('0 '), 'source slot empty'
    placed = click('JTMoneyOwner', target)['result']
    assert actor(placed, 'JTMoneyOwner')['cursor'].startswith('0 ')
    closed = qa('close JTMoneyOwner')['result']
    size1, slots1, gen1 = stored(closed)
    pending0(closed)
    assert actor(closed, 'JTMoneyOwner')['diskInventoryMatches'] is True
    assert actor(closed, 'JTMoneyOwner')['inventory'] == actor(before, 'JTMoneyOwner')['inventory'], 'player inventory changed'
    assert size1 == rows * 9, (size1, rows)
    padded = slots0 + [None] * (size1 - len(slots0))
    assert slots1[target] == padded[source] and slots1[source] is None
    for index in range(size1):
        if index not in (source, target):
            assert slots1[index] == padded[index], f'slot {index} changed'
    assert gen1 > gen0
    return {'sizeBefore': size0, 'sizeAfter': size1, 'generationBefore': gen0, 'generationAfter': gen1,
            'moved': [source, target], 'otherSlotsExact': True, 'cursor0': True, 'playerInventoryExact': True,
            'nonEmpty': sum(1 for s in slots1 if s), 'slotsSha256': hashlib.sha256(json.dumps(slots1, sort_keys=True).encode()).hexdigest()}


def grow():
    spawn_actors()
    status = qa('status')['result']
    assert 'ready=true' in status and 'provider=true' in status, status
    start = state('tiers-start')
    size, slots, gen = stored(start)
    rich = max(i for i, s in enumerate(slots) if s)
    save('start', {'tier': int(team(start)['tier']), 'bank': balance(start), 'size': size, 'generation': gen, 'richTail': rich})
    save('memberLocked', member_locked())
    if balance(start) < 500000:
        qa('setbank 500000')
    save('upgrade3', upgrade_via_gui(3, 500000))
    save('chest45', move(5, rich, 44, 'chest45'))
    qa('setbank 1000000')
    save('upgrade4', upgrade_via_gui(4, 1000000))
    save('chest54', move(6, 44, 53, 'chest54'))


def shrink_config():
    assert sha_file(CONFIG) == DONOR_CONFIG_SHA and SHRUNK_BACKUP.is_file() and sha_file(SHRUNK_BACKUP) == DONOR_CONFIG_SHA
    pid = int((ROOT / 'qa-server.pid').read_text())
    import ctypes
    handle = ctypes.windll.kernel32.OpenProcess(0x00100000, False, pid)
    if handle:
        ctypes.windll.kernel32.CloseHandle(handle)
        raise RuntimeError('cold rig required for config edit')
    import yaml
    settings = yaml.safe_load(CONFIG.read_text(encoding='utf-8'))
    settings['team_enderchest']['rows'] = 3
    for tier in settings['team_upgrades']['tiers'].values():
        tier['enderchest_rows'] = 3
    CONFIG.write_text(yaml.safe_dump(settings, allow_unicode=True, sort_keys=False), encoding='utf-8')
    save('shrinkConfig', {'allTierRows': 3, 'configSha256': sha_file(CONFIG), 'donorBackupSha256': DONOR_CONFIG_SHA})


def shrink():
    spawn_actors()
    snap = state('shrink-start')
    size, slots, gen = stored(snap)
    assert size == 54 and slots[53], (size, bool(slots[53]))
    result = move(6, 53, 52, 'shrink54')
    assert result['sizeAfter'] == 54
    save('shrinkRetains54', {**result, 'configuredRows': 3})
    back = move(6, 52, 53, 'shrink54-back')
    save('shrinkRetains54Back', back)


def restore_config():
    import ctypes
    pid = int((ROOT / 'qa-server.pid').read_text())
    handle = ctypes.windll.kernel32.OpenProcess(0x00100000, False, pid)
    if handle:
        ctypes.windll.kernel32.CloseHandle(handle)
        raise RuntimeError('cold rig required for config restore')
    CONFIG.write_bytes(SHRUNK_BACKUP.read_bytes())
    assert sha_file(CONFIG) == DONOR_CONFIG_SHA
    save('configRestored', {'configSha256': DONOR_CONFIG_SHA})


def sqlfault():
    spawn_actors()
    qa('close JTMoneyOwner')
    send('item replace entity JTMoneyMember hotbar.2 with minecraft:emerald[minecraft:custom_data={fixture:"tiers",member:true}] 7')
    send('item replace entity JTMoneyCo hotbar.3 with minecraft:gold_ingot[minecraft:custom_data={fixture:"tiers",co:true}] 5')
    send('item replace entity JTMoneyOwner hotbar.1 with minecraft:stone[minecraft:custom_data={fixture:"tiers",owner:true}] 3')
    time.sleep(.4)
    before = state('sqlfault-before')
    size, slots, gen = stored(before)
    assert size == 54 and slots[53]
    qa('core ec JTMoneyOwner')
    opened = await_slots('JTMoneyOwner', 90, 'sqlfault-open')
    assert actor(opened, 'JTMoneyOwner')['slots'] - 36 == 54
    qa('armsql')
    fault = click('JTMoneyOwner', 53)['result']
    assert fault['openChests'] == 0 and fault['acted']['cursorAfter'].startswith('0 ')
    assert fault['acted']['inventoryBefore'] == fault['acted']['inventoryAfter']
    q = {row['name']: row for row in fault['quarantine']}
    assert q['JTMoneyOwner']['actorQuarantined'] is True
    assert q['JTMoneyMember']['actorQuarantined'] is False and q['JTMoneyCo']['actorQuarantined'] is False
    for name in ('JTMoneyMember', 'JTMoneyCo'):
        assert actor(fault, name)['inventory'] == actor(before, name)['inventory']
    wal = ROOT / 'config/JustTeams/chest-intents.jsonl'
    rows = [json.loads(line) for line in wal.read_text(encoding='utf-8').splitlines() if line]
    frozen = next(row for row in reversed(rows) if row['phase'] == 'FROZEN')
    assert frozen['payload']['quarantinedInventory'] == fault['acted']['inventoryAfter']
    escrow = json.loads(frozen['payload']['cursorEscrow'])['slots'][0]
    assert escrow['item'] == slots[53]['item'], 'escrowed cursor must equal the tail stack'
    save('sqlFault54', {'leaseRevoked': True, 'cursorJournalledAndCleared': True, 'actorInventoryPreserved': True,
                        'unrelatedInventoriesPreserved': True, 'onlyActorQuarantined': True, 'opid': frozen['opid'],
                        'storedGenerationBefore': gen, 'tailItemSha256': hashlib.sha256(json.dumps(slots[53], sort_keys=True).encode()).hexdigest(),
                        'memberInventory': actor(before, 'JTMoneyMember')['inventory'], 'coInventory': actor(before, 'JTMoneyCo')['inventory']})


def relogin():
    qa('seedprofiles')
    for name in ('JTMoneyMember', 'JTMoneyCo'):
        send(f'player {name} spawn at 1 100 0')
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if any(a['name'] == name for a in state('await-' + name)['actors']):
                break
            time.sleep(.3)
        else:
            raise TimeoutError(name)
    after = state('sqlfault-relogin')
    old = report['sqlFault54']
    assert actor(after, 'JTMoneyMember')['inventory'] == old['memberInventory']
    assert actor(after, 'JTMoneyCo')['inventory'] == old['coInventory']
    size, slots, gen = stored(after)
    assert size == 54 and gen == old['storedGenerationBefore'], (size, gen)
    assert hashlib.sha256(json.dumps(slots[53], sort_keys=True).encode()).hexdigest() == old['tailItemSha256']
    q = {row['name']: row for row in after['quarantine']}
    assert q['JTMoneyOwner']['actorQuarantined'] and not q['JTMoneyMember']['actorQuarantined']
    assert 'pending=1' in after['status'], after['status']
    # Member must be refused the team chest while the team is frozen.
    try:
        qa('core ec JTMoneyMember')
        raise AssertionError('member opened a frozen team chest')
    except RuntimeError as error:
        assert 'CHEST_RECOVERY_REQUIRED' in str(error), error
    time.sleep(1)
    refused = state('sqlfault-member-ec')
    assert not actor(refused, 'JTMoneyMember')['menu'].endswith('TeamChests$Menu')
    save('sqlFaultColdRelogin', {'storedChestUnchanged': True, 'tailStackStillStored': True, 'escrowAlsoJournalled': True,
                                 'unrelatedInventoriesExact': True, 'actorQuarantineRestored': True, 'teamChestRefused': True,
                                 'pendingOne': True, 'note': 'stored chest still holds the stack; FROZEN escrow must be CANCELLED by operator, never returned (would duplicate)'})


STAGES = {'grow': grow, 'shrink-config': shrink_config, 'shrink': shrink, 'restore-config': restore_config,
          'sqlfault': sqlfault, 'relogin': relogin}
parser = argparse.ArgumentParser()
parser.add_argument('stage', choices=tuple(STAGES))
STAGES[parser.parse_args().stage]()
