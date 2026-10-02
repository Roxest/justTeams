# Donor refinements (Bukkit2.5.5)

Source `F:/Pixelclub/scratch/justteams-cutover-20261002/source/bukkit-decompile/eu/kotori/justTeams`. LIVE 2026-10-03 ICT,n=1,interval=point-in-time. Read-only,not acceptance.

## Damage/PvP/death

- `TeamDamageBonusListener:37-49`: enabled upgrades,Player attacker/projectile shooter,Player victim ONLY. Skip self/no attacker team/same team/multiplier<=1/damage<=0. Multiply attacker tier. PvP HIGH cancels allies before bonus MONITOR ignoreCancelled.
- `PvPListener:44-77`: Player victim/attacker or projectile shooter,skip self. Friendly=(sameTeam&&!pvpEnabled)||differentTeamAllied; allies ALWAYS blocked regardless PvP flags,check either ally set. Unteamed opponent allowed.
- Allowed PvP disables flying+allowFlight on BOTH if team_pvp.disable_fly_on_combat and flying||allowFlight; CREATIVE/SPECTATOR exempt,OP SURVIVAL/ADVENTURE not. pvp_fly_disabled. No mob/environment flight disable.
- `PvPListener:80-88`: ANY uncancelled Player damage cancels pending teleport,including fall/fire/mob; teleport_cancelled_damage. Cancelled friendly hit leaves teleport alive.
- `PlayerStatsListener:34-72`: ALL player deaths increment victim team deaths. killer=victim.getKiller() credited killer,not fatal DamageSource entity: includes projectile/later environmental death after attack. Killer team increments kills if victim unteamed/different team; no own-team kill. No ally check here. Port use atomic deltas,not donor async absolute totals.

## Bank numeric input/messages

`TeamGUIListener604..631`: 1s click cooldown,close GUI,raw prompt_bank_amount action=deposit/withdraw,await chat. Double.parseDouble(input): <=0 bank_invalid_amount,>1e9 bank_amount_too_large; no k/m/b/all/comma support,scientific decimal accepted. Reject nonfinite (donor NaN hole). Reopen afterward. ChatInputManager39..58 consumes one message,no trim/cancel/timeout; LIVE prompt promises cancel,but parser rejects it. Native cancellation is repair.

TeamCommand1083..1104 same parser. TeamManager2007..2116 NEVER calls FeatureRestrictionManager: bank fees ZERO. Deposit bank enabled/member/>0/economy/funds/cap SQL; withdraw member can_withdraw OR bypass.bank.withdraw. Success bank_deposit_success/bank_withdraw_success(amount,balance). Errors bank_insufficient_player_funds/bank_insufficient_funds/economy_error/bank_max_balance_reached.

LIVE prefix=`<#4C9DDE>[ᴛᴇᴀᴍꜱ]</#4C9DDE> `;sendMessage adds it,raw prompt doesn't. Invalid prose0.01..1M disagrees with actual>0..1e9. Success displays amount only. No Thai bold.

## Rename/join fee

TeamCommand936..1007: features.team_rename,justteams.rename OR admin,OWNER,different case-insensitive name,ASCII/unique. settings.rename_cooldown LIVE/default604800 SECONDS; team_rename_cooldowns(team_id PK,last_rename TIMESTAMP,created_at); remaining=cfg-(now-last)/1000,no bypass. feature_costs.economy.rename(default500),economy.enabled gate only,PLAYER-paid (not bank-first). Donor ignores withdraw result; port require payment+durable update before success.

ConfigManager132..163: getJoinFee=max(economy.team_join,largest positive justteams.joinfee.N),cap permission_based_join_fee.max_fee(10000);NOT additive. Null player/permission_based_join_fee.enabled=false ->base. join_fee_enabled REPLACES with join_fee_amount. Admission fees player-funded when feature costs/economy enabled.

## PAPI raw/display/sort/top

`util/PAPIExpansion.java` is authority; identifier justteams.

- No-team has_team=false. display uses placeholders.team_display.no_team (LIVE empty); tag_raw/tag_silent/colored_tag/gradient_tag empty. Most remaining params,including name/team_id/balance/count,return messages.no_team_placeholder LIVE `No Team`,not0 or empty. Null player ->empty. Unknown parameter WITH a team ->null.
- tag_raw returns raw tag. tag/team_tag and tag_silent render tag through MiniMessage or legacy ampersand and serialize MM; Team.getColoredTag actually raw tag. colored_tag/color_tag/gradient_tag: team gradient ->`<gradient:start:end>tag</gradient>`; else ChatColor section code+tag+section reset; no color ->raw. team_color returns enum name/default WHITE; color_code returns section code/default white. color_name raw team name. plain_name/plain_tag strip formatting.
- display uses team_display.format/team_color/team_icon/tag_color/tag_prefix/tag_suffix/show_icon/show_tag from placeholders.yml; uses configured display color rather than teams.color. LIVE format `<team_color><team_icon><team_tag></team_color>`,icon `⚔ `,team_color #4C9DDE,tag_color #FFD700,brackets [];show_icon/tag true. show_name is read but does not gate replacement.
- Role names OWNER/CO_OWNER/MEMBER; role_level=4/3/1 (not3/2/1). role_icon/color from placeholders.roles.<lowerrole>.icon/color. LIVE owner=`★ `/#FFD700,co_owner=`◆ `/#FF6B6B,member=`● `/#FFFFFF. is_admin owner/co-owner; is_member any role.
- tab_sort/sort_key=`%010d_ROLE_nameLower`,OWNER0/CO_OWNER1/MEMBER2/unknown3; no-team=`9999999999_3_nameLower`,null name zzzz. independent of GUI roles.priority values1/2/3.
- balance/team_balance/bank_balance DecimalFormat(settings.currency_format),LIVE empty string; bank_formatted `$`+format; bank_balance_raw String.valueOf(double). kdr `#.##`,deaths0 ->String.valueOf(kills). Dates `dd MMM yyyy` system zone;home_location Bukkit name+integer block coords,not CSV.
- Top caches10 per kills/balance/members/points,60s refresh descending. top_CATEGORY_POSITION_TYPE/leaderboard_CATEGORY_POSITION_TYPE; 1-based,out-of-range `---`; TYPE name/tag/colored_tag/gradient_tag/amount/value. Explicit top_balance_N_amount/value uses `#.##` (not currency format). Legacy top_name_N/top_tag_N/top_colored_tag_N uses settings.top_default_metric (default kills); top_amount_N still outputs kills even if list metric differs. Advertised top_balance_amount_N/etc appears broken: source parses `amount` as position ->`---`; treat usable alias support as repair. Source178..285,544..605.
