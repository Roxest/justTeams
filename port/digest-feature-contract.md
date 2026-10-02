# JustTeams 2.5.5 feature contract

Exact Bukkit2.5.5: `F:/Pixelclub/scratch/justteams-cutover-20261002`. LIVE SFTP 2026-10-02,n=1,interval=point-in-time; read-only,not acceptance.

## Config and messages

Original config/messages/gui/commands/placeholders/quests.yml in `config/JustTeams`. sendMessage prepends messages.prefix; raw/list do not. MiniMessage unparsed caller values; team_chat_format raw.

LIVE settings: max_team_size10/permission_based_team_size=true/min_name_length3/max_name_length16/max_tag_length6/max_description_length64/server-identifier=survival; settings.creation require_color=false/default_color=<white>/use_gui_color_selection=false/tag_optional=false. Tag gate features.team_tag. Stripped name ASCII alphanumeric+underscore,case-insensitive unique; tag alphanumeric<=6/raw<=255,description<=64. Formatting getter wrong top-level vs settings.*.

Capacity=max(base,tier,ONLINE owner justteams.team.size.N<=1000). team_upgrades.enabled/tiers.N.max_members/enderchest_rows/damage_bonus_percent/home_cooldown_reduction_percent/cost: caps10/100/6/15%/75%. Target-tier bank cost; LIVE bank digest. Donor reduction unused.

features.* default true. world_restrictions.enabled/disabled_worlds.<feature> Bukkit names. feature_costs.enabled/economy.enabled/items.enabled/economy.<feature>/items.<feature>/items.consume_on_use; bank-first then player (bank digest). Join fee team override or economy.team_join plus largest positive justteams.joinfee.N capped by permission_based_join_fee.max_fee; paid by player, not bank.

## Commands/chat

Roots team(t),teammsg(tm,tmsg). command-aliases.ALIAS.enabled=true: guild(g)/clan(c)/party->team; guildmsg(gmsg)/clanmsg(cmsg)/partymsg->teammsg. Extra literals: invites/unjoin/delhome/setdesc/setjoinfee/rename/public/glow/requests/ally/unblacklist/settings/serveralias/platform/quests|quest/points/chatspy|spy;enderchest|ec. team msg NOT donor literal. teammsg spam2s20/min,200chars.

features.team_chat; team_chat.character_enabled/character/require_space LIVE=true/#/false. Pending GUI input wins. Strip prefix, cancel global chat; online team members plus toggled justteams.chatspy outsiders. Ordinary chat rejects pass/password substring. teammsg online members only; content filter. Format player,prefix|player_prefix,suffix|player_suffix,team_name,message; teammsg adds team_tag/team_color+PAPI. Prefix/suffix legacy ampersand components. Auth separately checks justteams.command.* (setcolor justteams.setcolor) and roles/flags.

## Native GUI contract

GUI title/size/items.KEY.slot/material/name/lore/enabled/action. TeamGUI action default key;settings/no-team key. Recheck auth,cancel transfers.

|section (size)|keys:slots/action exceptions|
|---|---|
|no-team-gui27|create-team12,leaderboards14; title ᴛᴇᴀᴍ ᴍᴇɴᴜ|
|team-gui54|allies6,warps7,join-requests8,pvp-toggle45,ender-chest46,home47,upgrades-button48,sort49,bank50,team-settings-button52(action team-settings),disband-button or leave-button53; heads9..44; title ᴛᴇᴀᴍ - <members>/<max_members>|
|team-settings-gui27|change-tag11,change-description13,toggle-public15,back-button22|
|bank-gui27|deposit11,balance13,withdraw15,back-button22|
|member-edit-gui54|head4,promote/demote19,kick22,transfer25,promote-co-owner28,withdraw37,enderchest39,sethome41,usehome43,back49|
|join-requests/invites/warps/blacklist-gui54|no-data22,back49; generated head/warp slot lists via GuiSlotResolver; invites close53,blacklist header4|
|leaderboard-category-gui27|top-kills11,top-balance13,top-members15,back22; view54 back49|
|upgrades-gui45|current-tier11,next/max-tier15,upgrade-button22(action upgrade-purchase),back40|
|confirm-gui27|confirm11,cancel15; dynamic title|

Locked variants same slots. Settings placeholders team_tag/name/description,status_text/status/public_status. Quest title messages.quests_gui_title,size54,slots10..16/19..25/28..34.

## State/auth mutations

OWNER/CO_OWNER/MEMBER;elevated owner/co-owner. Preserve ten stored flags. MEMBER withdraw/ec/sethome/home=false,true,false,true;CO_OWNER promotion four true;demotion resets four.

Join: no current team,existing team,cap,not blacklisted. Public inserts MEMBER+clears all player's join_requests; private inserts team_id/player_uuid/created_at,repeat denied,60s request cooldown. unjoin removes own row. Accept/deny GUI elevated; donor accepts offline without fee,checks cap/blacklist; strengthen current-team+request transaction. teams.accept_requests controls ALLIES, not admission.

Settag/description/public/glow/PvP elevated; columns tag/description/is_public/glow_enabled/pvp_enabled. team_pvp.toggle_cooldown team-scoped,bypass.pvp.cooldown. Glow features.team_glow/settings.glow.enabled/only_show_own_team/range/check_interval. Transfer OWNER,different member,ConfirmGUI: owner_uuid=new,new OWNER four true,old MEMBER defaults. Owner leave/kick forbidden;flag edits OWNER-only.

Blacklist elevated,online target,not self/member/existing; team_blacklist(team_id,player_uuid,player_name,reason,blacklisted_by_uuid/name,blacklisted_at); no kick. Unblacklist team+player. Allies elevated; team_allies.enabled/max_allies/allow_request_toggle LIVE=true/10/true. Send denies self/allied/duplicate/cap; ally_requests(sender_team_id,target_team_id,requester_uuid,created_at). Accept incoming+target cap,atomic delete+ordered min/max team_allies(team_id_1,team_id_2); deny directional delete,remove either direction. accept_requests target enforcement NOT FOUND. Points BIGINT,add/remove>0,floor0; avoid async overwrite.

## Home/warp/world seam

CSV world,x,y,z,yaw,pitch full precision; home_server/warp.server_name/password. Home set/delete can_set_home,use can_use_home. Setwarp elevated,cap new name only; delete elevated OR warp.name==player.name. Password case-sensitive,input/cancel if absent. LIVE team_home warmup5/cooldown5; team_warps max5/warmup5/cooldown5. Per-player cooldowns/shared pending task; bypass.<home|warp>.cooldown skips both. Replace task; cancel offline/world change/distanceSquared>1. Fee before warmup,no refund; remote server proxy_not_enabled.

World seam: com.pixelclub.worlds.WorldNames.resolve(typed,loaded IDs,id->Worlds.config().get(id).bukkitName or WorldNames.defaultBukkitName(id)); exact WorldCommands374..385. Unknown null=deny. Vanilla world/ world_nether/world_the_end map overworld/the_nether/the_end. LIVE PRD/dev NO config/pxc-worlds; PRD Multiverse names home_world(+/nether),home_world_2(+/nether),spawn_world,survival_world_5(+/nether,/end),survival_world_6(+/nether,/end). Local rig omits sw6; not current map. Loaded Fabric map NOT FOUND; resolve against runtime levels,never guess sw/home.

## Placeholders/quests

PAPI identifier justteams. Core params display,has_team,tab_sort|sort_key,tag_silent,tag_raw,colored_tag|gradient_tag,name|team_name,tag|team_tag,owner,team_id,member_count,max_members,members_online,role/role_level/role_icon/role_color,kills/deaths/kdr,balance|team_balance|bank_balance,bank_formatted,bank_balance_raw,is_public,pvp_enabled,has_home,home_location,warp_count,max_warps,points|team_points,size,team_capacity,team_full,plain_name,plain_tag,join_date,created_at. Other aliases/fallback PAPIExpansion334..539. Top: top_CATEGORY_POSITION_TYPE/leaderboard aliases;legacy top_name_N/top_tag_N/top_colored_tag_N/top_amount_N,category amounts. Categories kills/balance/members/points;type name/tag/colored_tag/gradient_tag/amount/value.

LIVE quests.yml settings.enabled=false/auto_assign_count3. quests.ID.name/description/type/target/required/reward_points/reward_money/duration_seconds/icon. Types KILL_PLAYERS/KILL_MOBS/BREAK_BLOCKS/PLACE_BLOCKS/TRAVEL_DISTANCE/CUSTOM. Progress team_id/quest_id/progress/started_at/completed/claimed,PK team+quest; match nonexpired target,claim completed once by member. Points->teams.points; money->bank (Economy present),cap bypass max=-1. Donor claimed-before-payout flaw: use durable atomic claim+rewards. Runtime GUI/teleport/quest/events acceptance NOT FOUND.
