# JustTeams 2.5.3 (modified) → 2.5.5 Upgrade Plan

Target platform: **Arclight-fabric** (Spigot API only — no Paper Adventure methods).
Reference: extracted `JustTeams-2.5.5/` jar. Full class/method/string inventory: `reference-2.5.5-api.txt`.

## Missing classes (in 2.5.5, absent from src)

| Class | System |
|---|---|
| `gui/AllyGUI` | Allies |
| `gui/TeamCreationColorGUI` | Team creation color picker |
| `gui/UpgradesGUI` | Tier upgrades |
| `hooks/EternalCombatHook` | Combat-tag blocking of team commands |
| `hooks/TabHook` | TAB plugin integration |
| `listeners/TeamDamageBonusListener` | Tier damage bonus |
| `quests/Quest, QuestGUI, QuestListener, QuestManager, QuestProgress, QuestType` | Quests system |
| `team/PacketEventsGlowHandler` | Glow via PacketEvents |
| `team/TeamUpgradeManager` | Tier upgrades core |
| `util/GuiSlotResolver` | GUI slot parsing helper |

## Classes only in src (keep — local Arclight adaptations)
`config/MessageConfig`, `gui/admin/AdminDisbandConfirmGUI`, `gui/sub/MemberPermissionsEditGUI`, `gui/sub/MemberPermissionsListGUI`, `util/CacheManager`, `util/ServerCompatibility`

## Config gaps (repo → 2.5.5)
- `config.yml`: version 24 → 30. New sections: `team_upgrades` (5 tiers; hard caps enforced in code), `team_pvp` (toggle_cooldown 300, disable_fly_on_combat), `team_allies` (max_allies 10), `integrations.eternalcombat`, `settings.creation` (color GUI), `permission_based_team_size`, `permission_based_join_fee`, `economy.costs.team_creation/team_join`, `item_costs.team_creation`, faststats, notify-missing-packetevents
- `messages.yml`: 77 new keys (ally_*, quest_*, combat_tag_*, team_tier_*, join_fee_*, leaderboard_category_*, usage_ally*, pvp_toggle_cooldown, etc.)
- `gui.yml`: new `ally-gui` and `upgrades-gui` sections (+437 lines of other changes)
- `quests.yml`: new file (100 lines)
- `commands.yml`: commands-version 6 → 7

## Database schema additions (from 2.5.5 DatabaseStorage bytecode)
- `donut_team_allies` (team_id_1, team_id_2, UNIQUE team_ally_pair)
- `donut_team_ally_requests` (sender_team_id, target_team_id, requester_uuid, UNIQUE)
- `donut_teams` new columns: `accept_requests`, `alias`, `color`, `glow_enabled`, `is_public`, `join_fee_amount`, `join_fee_enabled`, `points`
- Plus tier/upgrade & quest progress storage (verify in decompiled source)

## TeamUpgradeManager API (from bytecode)
`isEnabled()`, `getMaxTier()`, `clampTier(int)`, `getMaxMembers(int)`, `getEnderChestRows(int)`, `getDamageBonusMultiplier(int)`, `getDamageBonusPercent(int)`, `getHomeCooldownReductionPercent(int)`, `getUpgradeCost(int)`, `canUpgrade(int)`. Hard caps: tier ≤ 10, members ≤ 100, damage bonus ≤ 15%, EC rows ≤ 6, cooldown reduction ≤ 75%.

## Arclight/Spigot compatibility rules (apply to all ported code)
1. Never call Paper-only `CommandSender#sendMessage(Component)` — serialize via `LegacyComponentSerializer.legacySection().serialize(component)` (pattern already used in `MessageManager`).
2. `Bukkit.createInventory` takes legacy String titles, not Components.
3. No `Player#sendActionBar(Component)`, `#kick(Component)`, `#displayName(Component)` — use Spigot equivalents.
4. Folia scheduler paths unnecessary; route through existing scheduler wrapper.
5. Keep `ServerCompatibility` detection (Arclight reports as such).

## Order of work
1. Decompile 2.5.5 (Vineflower, user-side) → `decompiled-2.5.5/`
2. Port core: TeamUpgradeManager + GuiSlotResolver + DB schema/storage changes
3. Port Allies (storage, TeamManager, AllyGUI, commands, messages)
4. Port Quests package + quests.yml
5. Port hooks (EternalCombat, TAB, PacketEventsGlowHandler) — all optional/softdepend
6. Port GUI additions (UpgradesGUI, TeamCreationColorGUI) + gui.yml
7. Update configs (config/messages/gui/commands/quests yml) with version bumps
8. Diff-review modified shared classes (TeamCommand, TeamManager, Team, DatabaseStorage, TeamGUI, PvPListener, PlayerStatsListener...)
9. Build on user PC (`mvn clean package`), fix errors, test on Arclight-fabric
