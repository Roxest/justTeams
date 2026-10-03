# Genuine DEV GUI acceptance — 7dd284fd

2026-10-03. Result: **GUI gate fails on unresolved upgrade fields**. These are rendered-client observations; no pure-Fabric GO or general permission-matrix claim.

## Pin and fixture

- Source `7893539322aa55f81357c091aaf880f3612fea3e`; app SHA256 `7dd284fdeaa3619feafd21b972b5c6fa4a90999f7b93d2c55d23ad8bc8e4d2c9` independently hashed before launch.
- Runtime handoff: DEV PID3762547, StartedAt2026-10-03T03:22:59.360552798Z, Done03:24:15.589Z, policyNo. Shared DEV is Arclight/AuthMe hybrid, not the pure-Fabric fixture. Host clock is reported ~441s behind PC; capture windows below use PC ICT, not mixed clocks.
- Sole approved renderer: existing watcher/PxcEyes2, default `dev.pixelclub.asia:24450`, existing client1.16.0, OP, stored synthetic MEMBER with chest-use=true/withdraw=false. No credential/config reads, client modification or auth setter. Runtime independently read exact server UUID `42bb1850-a13a-35e8-aa40-c9d2bb34a1ae`.
- n=34 PNG frames, window=PC10:37:17.440–10:50:25.518ICT, interval=per open/hover. Every PNG was viewed. Full native GUI metadata/status and PNGs: `F:/Pixelclub/scratch/justteams-cutover-20261002/gui-seen-7dd/` (outside git). Exact frame times are in paired JSONs.

## Executed / seen

| Surface | Actual result / representative PNG |
|---|---|
| Native auth positive | Real `/t` opens native chest GUI after profile autologin, twice: first10:37:17 and reconnect10:49:45. The source command guard requires `TeamsCore.authenticated`; offline DEV lacks native pxc-auth and the pinned optional AuthMe bridge binds exact online identity. No independent AuthMe bool RPC exists. `root`, `auth-relogin-root`. |
| Team root | Title `TEAM - 4/12`, four fixture heads/roles/names, configured buttons, PvP OFF, root tier2 and bank123.01. Ten root button tooltips inspected. `<name>` only in static `/team setwarp <name>` instruction is intentional command syntax. `root-*`, `self-head`. |
| Bank / member | Balance123.01 renders from fixture123.005; self withdrawal DISABLED, chest ENABLED, sethome DISABLED, usehome ENABLED, all self controls say View only/cannot edit. Bank withdrawal displays active prompt because existing OP bypass satisfies fallback2 (`TeamMenus:79`, `Access:15-16`). Locked-withdraw rendering is **not tested** with this OP profile. `bank-balance`, `bank-withdraw`, `member-readonly`, `member-{info,ec,sethome,usehome}`. |
| Settings / requests | MEMBER receives ELEVATED_REQUIRED; no actual settings/request GUI or accept/deny list was seen. Root still displays both buttons. Do not infer owner/CO rendering from this round. `settings`, `requests`. |
| Upgrades — HIGH | Title, current/next names and lore, and purchase tooltip display literal fields listed below. Balance substitutes123.01. No purchase clicked. `upgrades-{current,next,purchase}`. |
| Warps / allies | Actual empty pages and back controls render. No nonempty entries, protected warp prompt or accept/deny labels exercised. `warps`, `allies`. |
| EC | Actual4x9=36 slots, six rich fixture stacks: sword, potion, shulker, stone64, master ball, backpack. Sword name/lore/enchantment/attributes and Thai render; only open/hover/close, no slot click. `ec36`, `ec36-rich`. |
| Logout / reconnect | Controlled `/logout` then `/t` opens no GUI and displays permission refusal plus AuthMe login instruction. No native LOGIN_REQUIRED was displayed: upstream denial only, **not native C3-negative proof**. Normal disconnect/join autologin restores actual `/t` GUI. `auth-logout-denial`, `auth-relogin-root`. |

## Exact unresolved upgrade fields

- Title: `current_tier`, `max_tier`.
- Current item name: `current_tier`; lore: `current_max_members`, `current_enderchest_rows`, `current_damage_bonus`, `current_home_reduction`.
- Next item name: `next_tier`; lore: `next_max_members`, `next_enderchest_rows`, `next_damage_bonus`, `next_home_reduction`, `upgrade_cost`.
- Purchase lore: `next_tier`, `upgrade_cost`.
- Twelve distinct unresolved fields; all shown with literal angle brackets. Root tier/bank and self permission substitutions passed in observed frames. Thai is legible; no standalone legacy/hex/gradient/user-literal fixture was rendered in this member-only scope.

## Cleanup / limits

- Executed watcher `/close` and `/quit`, APIok; owned JavaPID199400 gone. Read-only final DEV `/t admin status`: ready=true/snapshot-health=true, teams1/members4/pending0/chests0/provider=true. `minecraft:list`: player0. DEV remains running, policyNo, Root-owned; no server control or PRD action.
- Final native inventory metadata and PNG retain only pickaxe1/protectionstone3 and no rendered carried stack (`final-inventory`), matching initial visible stacks. Cursor0 is **seen**, not a direct server-state query. Existing read-only interface exposes no carried item; no live exclusive-H2 query was opened, so this round does not assert exact durable rich-stock hash/generation conservation.
- No money action, purchase, inventory transfer, permission/flag, DB or six-file donor change. 45/54/shrink custody, owner/CO settings/requests, locked bank, explicit formatting/literal-user fixtures remain pending; prior native money/chest/fault/watchdog proofs retain their original hashes.
