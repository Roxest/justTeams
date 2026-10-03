# Exact donor — color / decoration

D=`F:/Pixelclub/scratch/justteams-cutover-20261002/source/bukkit-decompile/eu/kotori/justTeams/`;R=exact Bukkit2.5.5 jar built-ins;static only. TC=commands/TeamCommand.java; TM=team/TeamManager.java; DB=storage/DatabaseStorage.java; GUI paths below relative D/gui.

## setcolor mutation

Gate features.team_settings AND justteams.setcolor; node defaulttrue in R plugin.yml:175-177. Requires existing team and OWNER/CO_OWNER (TC:2255-2271;TM:777-785). args[1],optional args[2];extras ignored.

|input|color|gradient_start|gradient_end|
|---|---|---|---|
|reset,case-insensitive|NULL|NULL|NULL|
|named color,case-insensitive|UPPERCASE ChatColor enum|NULL|NULL|
|exact length2 &/§ color code|corresponding enum|NULL|NULL|
|single hex|NULL|original hex string|same original hex|
|two hex strings|NULL|first original hex|second original hex|

Legacy requires length2 and ChatColor.isColor;named valueOf(uppercase) also requires isColor(TM:765-773,819-832). Hex starts#,length7,parseInt(substring1,16),case preserved(TM:608-617). SQL and cache mutations match table(DB:1203-1219;team/Team:377-390);no name/tag change.

Branch:single# first;named/reset if second absent or either non#. RED GREEN succeeds RED;RED #ff0000 ignores second;#ff0000 RED invalid. Two# require both valid(TM:790-854). Async SQL->cache->online glow/TAB refresh(TM:797-876).

## team sort scope / persistence

Team state default JOIN_DATE(team/Team:64,244-260);each GUI copies at construction(TeamGUI:55-59). Left click advances JOIN_DATE->ALPHABETICAL->ONLINE_STATUS->JOIN_DATE;nonleft reverses. Then team.setSortType,async custom member_sort_type=enum.name(),this GUI rebuilds (TeamGUI:313-331;TeamGUIListener:378-380). Existing GUIs retain copied sort;new GUI sees team state.

Reload valueOf(uppercase),invalid ignored(TM:305-312). Team.id-scoped custom data(team/Team:409-414);donut_team_custom_data(team_id,data_key,data_value),key(team_id,data_key),timestamp(DB:3410-3415,3454-3459).

JOIN_DATE by date;ALPHABETICAL offline name lowercase/missing empty;ONLINE_STATUS descending boolean,stable ties;no role sorting(team/Team:277-278,544-550).

## filler / dummy / custom model data

TeamGUI clears inventory;fill-item material/name(default GRAY_STAINED_GLASS_PANE/" ") fills ONLY0..8 and45..53,then dummies,then dynamic heads9..44/configured slots,then buttons (TeamGUI:74,90-103,108-147). Empty middle slots remain empty. Heads/buttons overwrite dummies.

NoTeamGUI buttons first,fill only remaining null slots when fill-item exists,then dummies last (NoTeamGUI:50-79). Dummies overwrite buttons. Settings/member/bank fill remaining null slots;no dummy loader call found there (sub/TeamSettingsGUI:74-81;MemberEditGUI:122-128;BankGUI:70-76). Calls:TeamGUI300,NoTeamGUI79.

dummy-items.KEY.slot accepts Integer OR List containing Integer;other types skipped. Multiple slots retain list order;invalid negative/out-of-inventory skipped;empty list ignored. Section order=getKeys(false),last wins.Material fallback STONE;nonempty name/lore applied. custom-model-data presence applies getInt,enchanted=true enables glint. No enabled/action check or placeholder replacement (GUIManager:321-365). Dummy has no action.

Custom model=setCustomModelData(int);glint=UNBREAKING1+HIDE_ENCHANTS (util/ItemBuilder:125-144). Main normal button builder reads material/name/lore/action only,not custom-model-data/enchanted (TeamGUI:163-171). CMD scalar int.

## status / decoration render semantics

ItemBuilder parses MiniMessage then forces italic=false for names/lore and legacySection serializes (util/ItemBuilder:60-79). Status substituted before parsing;configured tags create styling.Dummy name/lore parsed directly.

Main <status>=PvP status-on/status-off from team-gui.items.pvp-toggle,defaults "<green>ON"/"<red>OFF";<permission_prompt>=can-toggle-prompt/cannot-toggle-prompt based E role (TeamGUI:202-206). R gui.yml:241-242 instead declares status-enabled/status-disabled;exact code ignores these keys. Settings <status_text>/<status>/<public_status> uses toggle-public.status-public/private,defaults colored Public/Private (sub/TeamSettingsGUI:87-91).

Member withdraw_status/enderchest_status/set_home_status/use_home_status/promote_co_owner_status are messages.yml raw status_enabled/status_disabled;empty/missing falls back "<green>ENABLED"/"<red>DISABLED" (MemberEditGUI:149,169-176). Main head status_indicator="<COLOR>"+ICON+" </COLOR>",using placeholders.yml status.online/offline.icon/color;defaults dot,#00FF00/#FF0000 (TeamGUI:289-292;util/GuiConfigManager:168-175). role icon/name/color keys=roles.ROLELOWER.icon/name/color (GuiConfigManager:156-165).

Sort lore=selected/unselected prefix + sort.TYPELOWER.icon + sort.TYPELOWER.name,where TYPE is join_date/alphabetical/online_status. Placeholder keys sort.selected_prefix/unselected_prefix;defaults "<green>▪ <white>"/"<gray>▪ <white>" (TeamGUI:277-282;GuiConfigManager:178-191;R placeholders.yml:193-208).
