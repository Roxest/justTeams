# digest — GUI/actions (JustTeams Bukkit 2.5.5, SHA256 `36c725c1c15af`)

**Source:** `F:/Pixelclub/scratch/justteams-cutover-20261002/source/bukkit-decompile/eu/kotori/justTeams/` (S), built-in jar resources(R). **Usage:** not sampled. **Written:**2026-10-03;static.

## Identity
TC=commands/TeamCommand.java;TM=team/TeamManager.java;GL=gui/TeamGUIListener.java;GUI files relative S/gui. O=owner,E=owner/co-owner,M=member;C.x=justteams.command.x;feature prefix features.

## Commands (with real usage)
Declared != enforced:register never setPermission(util/CommandManager77-88);feature check only config(TC386-393);no generic C.* checks. Default-true C.literal(R plugin.yml:85-177),except setdesc=C.setdescription,ec=C.enderchest. Nodes absent for invites/glow/settings/points/rename/setjoinfee/delhome/ally/blacklist/unblacklist/serveralias/platform.

|literal/arguments|feature;effective check|
|---|---|
|create NAME TAG|team_creation;unteamed|
|disband / invite PLAYER / invites / accept TEAM / deny TEAM|team_disband/O;team_invites(invite E)|
|join TEAM / unjoin TEAM|team_join_requests / no feature|
|settag TAG / setdesc TEXT...|team_tag/team_description;E|
|public / glow / pvp / requests|team_public_toggle/team_glow/team_pvp_toggle/team_join_requests;E|
|settings / points|no feature;E/M|
|setcolor COLOR [COLOR]|team_settings/E;justteams.setcolor CHECKED;reset,named,legacy,hex-gradient|
|rename NAME / setjoinfee AMOUNT|team_rename/O AND (justteams.rename OR admin);economy/E|
|ally [add/request/remove/break/accept/deny TEAM;toggle;list]|team_allies;changes E|
|chat / chatspy,spy / teammsg TEXT...|team_chat;spy justteams.chatspy CHECKED;teammsg no feature|
|serveralias set SERVER ALIAS...;remove SERVER;list|no feature;admin helper CHECKED;alias<=64|
|platform|bedrock_support.enabled (not features.*)|

Refs TC:477-1312,1420-1596,1683-1689,1793-1854,2081-2100,2255-2271.  Kick CO_OWNER target requires O(`TM:1264`);grant=can_promote_to_co_owner(`TM:1352-1355`). Root1s+30/min(TC:73-79,368-383).

Admin=isOp OR * OR justteams.admin;only reload accepts console. /team admin [gui],disband TEAM,home TEAM,enderchest TEAM,points TEAM add/remove/set LONG(`TC:82-88,1611-1666,1693-1735,1853-1854`). Chest additionally justteams.admin.enderchest(`TM:242-245`); performance additionally justteams.admin.performance:database/cache/tasks/optimize/cleanup(`TC:1968-2000`). 

## Permissions and nodes
Flag writes O-only(TM2405-2407). GUI opens nonself+O or CO_OWNER->MEMBER(GL265-280),ignores can_edit_members/co_owners (TeamPlayer.canEditPlayer182-201). Slot28 O->CO_OWNER rechecked(GL523-543). Promote/demote flags=TTTT/FTFT(storage/DatabaseStorage:1600).

## Config keys (observed values)
R gui.yml v15: root title/size/items.KEY.{slot,material,name,lore,enabled,action},fill-item.{material,name},dummy-items with slot(int/list),custom-model-data,enchanted(`GUIManager:323-354`). Built-in:

|root(size)|key+slot;action if different|
|---|---|
|no-team-gui(27)|create-team12,leaderboards14|
|team-gui(54)|allies6,warps7,join-requests8,player-head9..44,pvp-toggle45,ender-chest46,home47,upgrades-button48,sort49,bank50,team-settings-button52->team-settings,disband-button/leave-button53|
|team-settings-gui(27)|change-tag11,change-description13,toggle-public15,back-button22|
|member-edit-gui(54)|player-info-head4,promote-button/demote-button19,kick-button22,transfer-button25,toggle-promote-co-owner28,withdraw-permission37,enderchest-permission39,sethome-permission41,usehome-permission43,back-button49|
|bank-gui(27)|deposit11,balance13,withdraw/withdraw-locked15,back-button22|
|join-requests/invites/warps/blacklist-gui(54)|dynamic player-head/team-icon/warp-item/player-head9..44;no-requests/no-invites/no-warps/no-blacklisted22;back-button49;invites close-button53;blacklist blacklist-header4,error-loading22|
|upgrades-gui(45)/confirm-gui(27)|current-tier11,next-tier/max-tier15,upgrade-button22->upgrade-purchase,upgrade-button-locked22->upgrade-locked,back-button40 / confirm11,cancel15|

R gui.yml:27-840,1384-1503;leaderboard-category-gui27:top-kills11,top-balance13,top-members15,back-button22;leaderboard-view-gui54:team-head,back49.`TeamGUI:108-147,163-171`,`MemberEditGUI:68-126`. Slot28 !self+CO_OWNER target+O. Self flags:-view,same slots,read-only;feature-disabled flags hidden. Member/settings/no-team IGNORE config action/use key(`MemberEditGUI:125-135`,`sub/TeamSettingsGUI:72`,`NoTeamGUI:74`);main/request action fallback key.
Locked resources:join-requests-locked8,bank-locked50,ender-chest-locked46,settings-locked52,warps-locked7,pvp-toggle-locked45. Main selects ONLY chest locked,hides unauthorized requests/settings(`TeamGUI:108-145`);bank withdraw-locked(`BankGUI:66-71`). Dynamic slots:ordered,duplicates kept,negative stripped,fallback[start,end),truncate,no upper bound(`util/GuiSlotResolver:19-36`).

Context vars<>:title members/max_members;items balance,tier,status,permission_prompt,sort_status_join_date/alphabetical/online_status,team_name/tag/description;head status_indicator,role_icon,player;lore role/joindate/server(TeamGUI:63,206,234-238). Member player_name,target_name,role_icon,role,joindate,withdraw_status,enderchest_status,set_home_status,use_home_status,promote_co_owner_status,platform_indicator(MemberEditGUI:149). Settings team_tag/name/description,status_text/status/public_status(sub/TeamSettingsGUI:91). Bank balance;request player_name/status_indicator;invite team_name/tag/inviter/member_count/max_members/description;warp warp_name,server_name/server,warp_protection_status,delete_prompt;ally team/tag/members;blacklist player_name/blacklister/date/reason;leaderboard rank/team_name/team_tag/statistic_name/statistic_value(BankGUI100,JoinRequestGUI91,InvitesGUI94,WarpsGUI94,AllyGUI118-140,BlacklistGUI147-148,LeaderboardViewGUI65-87).

## Events listened / cancelled
GUI transfers cancelled(GL1438-1444). Request player-head+player_uuid left accept/right deny;invite team-icon+team_id/team_name same;warp actual warp_item+warp_name/right delete;ally remove-ally:ID/ally-request:ID->confirm accept/cancel deny;blacklist remove-blacklist:UUID(`GL:689-752,1343-1392,1451-1495`).

PvP code status-on/off vs R status-enabled/disabled(TeamGUI:202).

## Data owned (exact formats)
can_withdraw/can_use_enderchest/can_set_home/can_use_home/can_promote_to_co_owner. Slot28 storage permission=promote_co_owner(`GL:542;storage/DatabaseStorage:1559-1563`). Sort custom member_sort_type=JOIN_DATE/ALPHABETICAL/ONLINE_STATUS(TeamGUI:322-330).

## Callers and coupling (from the Phase-0 sweep)
Vault Chat prefix/suffix,error->empty(JustTeams724-751),legacy ampersand components. Listener:team_chat feature,pending input wins;team_chat.character_enabled/character/require_space=true/#/false;cancel global+strip marker;reject pass/password;members+outsider spies. Vars player,prefix/player_prefix,suffix/player_suffix,team_name,message;no PAPI,team_tag,team_color,length/rate limits(listeners/TeamChatListener90-137). teammsg:200 UTF16 chars,2s,20/min,substring filter;members,NO spies;adds team_tag/team_color(UNPARSED)+PAPI(commands/TeamMessageCommand45-55,73-84,160-167,220-241).

## Traps
Admin manage/member HARDCODED despite resources. Manage1 edit-description,2 rename-team,3 edit-tag,9 toggle-public,10 toggle-pvp,11 edit-balance,12 edit-stats,13 view-enderchest,14 teleport-home,17 disband-team;member-UUID heads;48 prev-members,50 next-members,45 back-button,53 refresh(`admin/AdminTeamManageGUI:59-117`). Inputs:text;balance Double>=0;stats2 nonnegative ints(`GL:993-1136`). Member19 toggle-withdraw,20 toggle-enderchest,21 toggle-sethome,22 toggle-usehome,23 toggle-promote-co-owner(CO_OWNER),29 promote-member,30 demote-member,33 kick-member,45 back-button(`admin/AdminMemberEditGUI:65-81`).
Glow features.team_glow+PacketEvents;constructor IGNORES settings.glow.enabled. check_interval(default20ticks);skip self;target team glow_enabled;same world+distanceSquared<=range*range(30);only_show_own_team=true needs receiver same team. Color team else settings.glow.colors.owner/co_owner/member=DARK_RED/RED/WHITE;cache[receiver][target] skips unchanged-color packets(`team/GlowManager:74-87,94-131,309-324,337-388`). No range clamp/int-square overflow. Repair:finite range>=0,double square,interval>=1,server-thread entity work;unset per-viewer on failed predicate,never global setGlowing.

## Not found
Live flags/usage/rendering not sampled;generic C.* checks absent;no credential/server/application edits.

