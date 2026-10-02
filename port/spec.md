# Implementation contract

Main owns orchestration, settings, authorization, bank logic, typed commands/native screens/events, runtime acceptance, release and cutover.
Mechanical units are isolated Git worktrees and merged after reviewing their complete diffs.

## Store ABI

Package `com.pixelclub.teams.store`. `TeamStore` receives `ConnectionFactory` (`Connection open() throws SQLException`) plus validated table prefix. It never catches SQL errors as absent teams/success and never changes team money outside conditional transaction APIs. All SQL values use prepared statements; prefix must match `[A-Za-z0-9_]+`.

`Team` and `Member` expose immutable `Map<String,Object> fields()` preserving every donor column plus typed `id()/uuid()/teamId()/role()/name()/owner()/balance()/tier()/pvp()/isPublic()/location()/field(String)/flag(String)` convenience access. Exact column labels from JDBC metadata are lowercase; values/DOUBLE opening balances remain unchanged.

Public reads: `Optional<Team> team(int)`, `teamOf(UUID)`, `teamNamed(String)`, `Optional<Member> member(UUID)`, `List<Member> members(int)`, `List<Team> top(String category,int limit)`, `List<Map<String,Object>> rows(String suffix,String whereColumn,Object value)`, `Map<String,String> requiredSchema()`.

Public transactional writes: `create(name,tag,owner,ownerName,serverName)` returns new team; `invite(teamId,inviter,target,expiresEpochMillis)`; `accept(target,teamId,maxMembers)` requires matching invite/current no team/not blacklisted/cap in one transaction; `leave/kick/removeMember` must never remove owner; `setMemberRoleAndFlags`; `updateTeam(teamId,expectedOwner,Map)` whitelisted columns only; `setMemberFlags`; `deleteTeam(teamId,expectedOwner)` with cascading donor data only after authorized caller main check; `setWarp(teamId,name,location,serverName,password,maxWarps)`/`deleteWarp`; `upgrade(teamId,expectedTier,newTier,cost)` single conditional balance+tier update; `mutateBank(opid,teamId,player,delta,maxBalance,requireWithdraw,bypass)` atomically validates current membership/flag, writes unique opid and delta with balance condition/cap. Duplicate opid returns recorded result, never reapplies. Do not expose unguarded += balance helpers.

Additive tables: `pxc_teams_operations` (opid primary key, team_id, player_uuid, kind, amount, phase, payload, created_at, updated_at), `pxc_teams_chests` (team_id primary key, format_version, inventory_json LONGTEXT or backend equivalent, original_sha256, generation BIGINT, updated_at). Maintain foreign keys where available; never drop donor data. Journal API: `beginOperation/updateOperation/operation/pendingOperations`, `isFrozen(team,player)`, `freezeOperation`. Native chest CAS load/save JSON requires expected generation; originals stay in `donut_team_enderchest` for reverse conversion.

No live DB access is allowed from tests. H2 isolated tests must cover denied flag, optional bypass, cap, negative underflow, owner removal denial, invite/current membership/cap, duplicate bank opid, tier+balance atomic update, schema mismatch, and chest CAS rejection. Production configs, secrets, UUID/name rosters or monetary files never enter git.
