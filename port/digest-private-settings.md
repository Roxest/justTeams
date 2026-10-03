# Exact private donor settings handoff - 2026-10-03

Scope: exact six YAMLs plus settings.yml existence, hashes and 20 safe selector groups only.
No private copies found in authorized justTeams scratch; Public Roxest2.4 resources excluded.
Read-only fixed SFTP paths under PRD `plugins/justTeams/`; no remote command, SQL, restart, player,
money, deployment or shared-DEV write. Source helpers retain no private bytes and suppress connector
diagnostics. No credential/licence/webhook/account/item content is output or committed.

Evidence rung **file state**, n=3 narrow reads, window07:33:17-07:44:28 ICT10-03, interval per read;
all byte hashes stable. Mtimes are older than the fresh read, not the age of this observation.
Loaded plugin/version/config memory was NOT queried; these are current disk hashes, not loaded proof.

| File | Bytes | Modified ICT | SHA256 |
|---|---:|---|---|
| config.yml | 19903 | 06-21 01:53:12 | 13b9c9cfbd26d2053fb54ce40244fe5209ba33261cde77703e35e92fe62e0095 |
| commands.yml | 2046 | 06-12 03:14:57 | 2ef9edec478b0def636b1698a2058cd4f61b7ba232cc6712e3adbf068cb6a23c |
| gui.yml | 46447 | 06-12 06:45:31 | 4aed3365e6af784b9470ce6490473d30020680ca23570abfcb777e95b17786e8 |
| messages.yml | 40374 | 06-12 03:10:25 | 3dcb5c3f9ed56f08be2b52803c4591c587bb768089c37a7b4fb620dfb92cb125 |
| placeholders.yml | 9244 | 06-12 04:20:54 | e8132174ae24623f8cf4b887ca5884ed3b5fe37c291059b89e5ceee987059a84 |
| quests.yml | 2575 | 06-14 05:04:06 | 5d27cdf0c40e144820b2377412560134c467ae8ffdf03976ad67127bc4b26557 |

All six duplicate-key counts=0. settings.yml absent: settings are a config.yml section; do not
invent/copy a seventh YAML. Settings.load reads exactly these six files. Disk donor jar remains
JustTeams2.5.5 SHA36c725c1c15afcb546142b9933a40ee14499cffe55daa1d415f9cca7c4bef8af,
mtime08-09 00:23 ICT. Exact baseline, not public2.4; no loaded-SHA claim.

## Safe projection / binding (20 groups maximum)

1. storage.type=mysql; source secrets/host/catalog withheld. DEV must use isolated H2, not this target.
2. team_bank: enabled=true, max_balance=1,000,000,000; Settings/Core->BankService cap.
3. feature_costs gates: enabled=true/economy=true/items=false; native cost/item gates bind.
4. economy: creation/join/home/warp/enderchest/deposit/withdraw=0; rename50,000/sethome250,000/
   setwarp500,000. Copy verbatim; no default100/200/500 or new fee interpretation.
5. join_permission: enabled=false/max_fee10,000. Core.joinFee honors disabled override; row fees retained.
6. capacity: max_team_size10, permission_based_team_size=true; Core.capacity uses tier/online-owner node.
7. creation: require_color=false/default_color=<white>/GUI-selection=false/tag_optional=false.
8. rename_cooldown604800 seconds; Core.rename + transactional cooldown, player-paid donor fee.
9. glow: settings.enabled=false, own-team=true/range30/interval20; role colors darkred/red/white.
   Active cutover preserves disabled gate; future TAB-owned outline-color parity remains separate.
10. upgrades.enabled=true.
11. tiers2/3/4/5 costs250k/500k/1M/2M; actual YAML keys are INTEGER, not String.
12. enderchest.enabled=true/rows3; tier rows still require numeric lookup + native receipt/CAS custody.
13. warps.max_warps5; Core maxWarps binds. No enabled leaf is present at this section.
14. world_restrictions.enabled=true.
15. disabled_worlds: ec=world_nether/world_the_end; sethome/setwarp/home=pvp_world/event_world;
    warp=pvp_world; bank=creative_world. Native resolves loaded Bukkit aliases, never guesses SW/home.
    Bank restriction is declared but no exact donor bank call-site enforcement was found; do not
    invent a new restriction/fee. Actual target world/registry/SW6/liveEnd binding remains owner gate.
16. all observed boolean features=true (bank/home/warps/tag/chat/glow/membership included).
    Core.check maps command feature gates; read-only inactive umbrella keys are not new removals.
17. aliases: guild/clan/guildmsg/clanmsg=true; party/partymsg=false; Settings.commandAlias/register binds.
18. display: format=<team_color><team_icon><team_tag></team_color>, color#4C9DDE/tag#FFD700,
    show_tag/show_icon=true. PAPI/menus use copied placeholders; rendered acceptance remains open.
19. quests.settings.enabled=false; TeamMenus gate preserves disabled donor, not feature deletion.
20. GUI active action set matches native teamAction: allies/bank/disband/ec/home/requests/leave/pvp/
    sort/settings/upgrades/warps. Entire gui/messages hashes, not just this projection, must be copied.

## HIGH found, fixed source; native targeted acceptance pending

Original83cdaa4/SHA4ad8e0ac9888c671cc2aabdf98b8d126d2512956edd05c32c48d5171ea1a3f87:
Settings.lookup used String map.get; exact integer tiers disappeared. Core245-247 refused MAX_TIER,
capacity127 fell back; active tier costs/caps were ignored. Synthetic unquoted2/cost250000 with the
immutable original jar reproduced native_string_lookup_cost=null. Root fixed numeric fallback and
atomic all-six-map publication in source4d1d7f9; new immutable jar
SHAfa1343d04899f00e0a231ab0a78eaeccfb41e395043b3b379afdba90b309c9ed reproduces250000.
This closes source/synthetic defect, not loaded native tier/reload acceptance.

Additional cap/reload parity: donor DB1296 capped=maxBalance>0, <=0 uses uncapped UPDATE;
ConfigManager409 getter samples current config, reload35-37 replaces config; TM2020 samples each
deposit. Old native positive-only/final cap and reload-not-refresh differed. Root4d1d7f9 supports
finite<=0 and synchronized cap refresh on reload; current PRD1B/economics unchanged. Parent reports
unit regression pass; targeted native validation pending. No unrelated active HIGH established here.

## Private copy / final bundle plan (not executed)

Coordinator takes a fresh cold byte snapshot of these exact six files and source jar, writes private
owner-only copies outside git/server backup directories, and compares every SHA. Place as
config/JustTeams (uppercase J) only while target is cold; never let packaged/scaffold defaults replace
donor economics/text/actions. Retain private SQL credentials for original PRD only. Isolated DEV/H2
requires a narrowly documented storage-only override and synthetic actors, no PRD keys/licences.
No staging/restart by this worker; shared DEV is Tools-owned after PAPI04:12 release.

Final target pin must use the post-fix application/overlay, exact MC1.21.1 full item registry,
Pack/Impactor/auth/LP/Worlds+Fantasy/PAPI/TAB and coupled owners' current bundle releases. Disk
copy equality is not loaded parity: capture target boot/version, six-file loaded/reload results,
numeric tier cost/cap, auth/permissions/world denials/PAPI and rendered GUI evidence separately.
Fresh cold22-table/chest/account/world snapshots and approved window remain required; this audit
is current live file-state preparation, not cold conversion or GO.
