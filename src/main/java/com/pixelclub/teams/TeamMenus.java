package com.pixelclub.teams;

import com.pixelclub.teams.store.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemLore;
import java.util.*;

/** Donor slot/title/layout data rendered through vanilla server-side menus. */
public final class TeamMenus {
    private final TeamsCore core;
    public TeamMenus(TeamsCore core){this.core=core;}
    @FunctionalInterface private interface Button{void click(ServerPlayer player)throws Exception;}
    private final class Screen {
        final ServerPlayer player;final String section;final SimpleContainer inventory;final Map<Integer,Button> buttons=new HashMap<>();final int teamId;final Component title;
        Screen(ServerPlayer player,String section,int fallback,String defaultTitle)throws Exception {
            this.player=player;this.section=section;teamId=core.store.teamOf(player.getUUID()).map(Team::id).orElse(0);
            int size=number(section+".size",fallback);if(size<9||size>54||size%9!=0)throw new TeamsCore.Refusal("GUI_SIZE_INVALID");inventory=new SimpleContainer(size);
            title=Texts.of(render(player,string(section+".title",defaultTitle)));
            ItemStack filler=item("GRAY_STAINED_GLASS_PANE"," ",List.of());for(int i=0;i<size;i++)inventory.setItem(i,filler.copy());
        }
        void button(String key,int slot,String material,String label,Button button)throws Exception {
            if(!bool(section+".items."+key+".enabled",true))return;
            String path=section+".items."+key;int actual=number(path+".slot",slot);if(actual<0||actual>=inventory.getContainerSize())throw new TeamsCore.Refusal("GUI_SLOT_INVALID");
            Object lore=value(path+".lore");List<String> lines=new ArrayList<>();if(lore instanceof Collection<?> list)for(Object line:list)lines.add(render(player,line.toString()));
            inventory.setItem(actual,item(string(path+".material",material),render(player,string(path+".name",label)),lines));if(button!=null)buttons.put(actual,button);
        }
        void direct(int slot,String material,String label,List<String> lore,Button button){inventory.setItem(slot,item(material,label,lore));if(button!=null)buttons.put(slot,button);}
        void open(){player.openMenu(new SimpleMenuProvider((id,inv,p)->new Menu(id,inv,this),title));}
    }
    private final class Menu extends ChestMenu {
        final Screen screen;long last;final long opened=System.currentTimeMillis();
        Menu(int id,Inventory player,Screen screen){super(type(screen.inventory.getContainerSize()/9),id,player,screen.inventory,screen.inventory.getContainerSize()/9);this.screen=screen;}
        @Override public void clicked(int slot,int button,ClickType type,Player actor) {
            if(!(actor instanceof ServerPlayer player)||!actor.getUUID().equals(screen.player.getUUID())||type!=ClickType.PICKUP||button>1)return;
            long now=System.currentTimeMillis();if(now-opened<250||now-last<250)return;last=now;
            Button handler=screen.buttons.get(slot);if(handler==null)return;
            core.safely(player,()->{core.check(player,"gui");int current=core.store.teamOf(player.getUUID()).map(Team::id).orElse(0);if(current!=screen.teamId){player.closeContainer();throw new TeamsCore.Refusal("STALE_GUI");}handler.click(player);});
        }
        @Override public ItemStack quickMoveStack(Player player,int slot){return ItemStack.EMPTY;}
        @Override public boolean stillValid(Player player){return core.ready()&&player.getUUID().equals(screen.player.getUUID());}
    }
    public void team(ServerPlayer player)throws Exception {
        Team team=core.store.teamOf(player.getUUID()).orElse(null);
        if(team==null){Screen menu=new Screen(player,"no-team-gui",27,"ᴛᴇᴀᴍ ᴍᴇɴᴜ");menu.button("create-team",12,"ANVIL","&aCreate team",p->core.prompt(p,"Team name",name->core.safely(p,()->core.prompt(p,"Team tag",tag->core.safely(p,()->core.create(p,name,tag))))));menu.button("leaderboards",14,"BOOK","&eTop teams",p->top(p,null));menu.open();return;}
        Screen menu=new Screen(player,"team-gui",54,"ᴛᴇᴀᴍ - <members>/<max_members>");
        menu.button("allies",6,"GREEN_BANNER","&aAllies",this::allies);menu.button("warps",7,"ENDER_PEARL","&aWarps",this::warps);menu.button("join-requests",8,"PAPER","&aRequests",p->{core.elevated(p);requests(p);});
        List<Member> members=core.store.members(team.id());for(int i=0;i<Math.min(36,members.size());i++){Member member=members.get(i);menu.direct(9+i,"PLAYER_HEAD",Objects.toString(member.name(),member.uuid().toString()),List.of(member.role()),p->member(p,member.uuid()));}
        menu.button("pvp-toggle",45,"DIAMOND_SWORD","&aPvP: "+team.pvp(),p->core.setting(p,"pvp_enabled",!core.requireTeam(p).pvp()));
        menu.button("ender-chest",46,"ENDER_CHEST","&aTeam chest",p->core.chests.open(p));menu.button("home",47,"RED_BED","&aTeam home",p->core.teleport(p,"home",null,null));
        menu.button("upgrades-button",48,"NETHER_STAR","&aUpgrades",this::upgrades);menu.button("sort",49,"HOPPER","&aSort",this::team);menu.button("bank",50,"GOLD_INGOT","&aBank",this::bank);
        menu.button("team-settings-button",52,"COMPARATOR","&aSettings",this::settings);
        menu.button(team.owner().equals(player.getUUID())?"disband-button":"leave-button",53,"BARRIER",team.owner().equals(player.getUUID())?"&cDisband":"&cLeave",p->confirm(p,"confirm",()->{if(core.requireTeam(p).owner().equals(p.getUUID()))core.disband(p);else core.leave(p);}));menu.open();
    }
    public void bank(ServerPlayer player)throws Exception {
        core.check(player,"bank");Team team=core.requireTeam(player);Screen menu=new Screen(player,"bank-gui",27,"Team bank");
        menu.button("deposit",11,"LIME_DYE","&aDeposit",p->{String op=UUID.randomUUID().toString();int id=core.requireTeam(p).id();core.prompt(p,"Deposit amount",text->core.safely(p,()->{if(core.requireTeam(p).id()!=id)throw new TeamsCore.Refusal("STALE_GUI");core.bank(p,true,Double.parseDouble(text),op);}));});
        menu.button("balance",13,"GOLD_INGOT","&e"+Texts.money(team.balance()),null);
        menu.button("withdraw",15,"RED_DYE","&cWithdraw",p->{String op=UUID.randomUUID().toString();int id=core.requireTeam(p).id();core.prompt(p,"Withdraw amount",text->core.safely(p,()->{if(core.requireTeam(p).id()!=id)throw new TeamsCore.Refusal("STALE_GUI");core.bank(p,false,Double.parseDouble(text),op);}));});back(menu,22);menu.open();
    }
    public void settings(ServerPlayer player)throws Exception {
        core.elevated(player);Screen menu=new Screen(player,"team-settings-gui",27,"Team settings");
        menu.button("change-tag",11,"NAME_TAG","&aTag",p->core.prompt(p,"Tag",text->core.safely(p,()->core.setting(p,"tag",text))));
        menu.button("change-description",13,"WRITABLE_BOOK","&aDescription",p->core.prompt(p,"Description",text->core.safely(p,()->core.setting(p,"description",text))));
        menu.button("toggle-public",15,"OAK_DOOR","&aPublic",p->core.setting(p,"is_public",!core.requireTeam(p).isPublic()));back(menu,22);menu.open();
    }
    public void member(ServerPlayer player,UUID who)throws Exception {
        core.check(player,"settings");Team team=core.requireTeam(player);Member target=core.store.member(who).orElseThrow(()->new TeamsCore.Refusal("NO_MEMBER"));if(target.teamId()!=team.id())throw new TeamsCore.Refusal("STALE_GUI");
        Screen menu=new Screen(player,"member-edit-gui",54,"Member: "+core.playerName(who));menu.direct(4,"PLAYER_HEAD",core.playerName(who),List.of(target.role()),null);
        menu.button("promote",19,"LIME_DYE","&aPromote / Demote",p->{core.role(p,who,!target.role().equals("CO_OWNER"));member(p,who);});menu.button("kick",22,"BARRIER","&cKick",p->confirm(p,"Kick",()->core.kick(p,who)));
        menu.button("transfer",25,"NETHER_STAR","&eTransfer owner",p->confirm(p,"Transfer",()->core.transfer(p,who)));
        String[] flags={"can_withdraw","can_use_enderchest","can_set_home","can_use_home"};int[] slots={37,39,41,43};String[] keys={"withdraw","enderchest","sethome","usehome"};
        for(int i=0;i<flags.length;i++){String flag=flags[i];menu.button(keys[i],slots[i],"LEVER","&e"+flag+": "+target.flag(flag),p->{Member fresh=core.store.member(who).orElseThrow();core.editFlag(p,who,flag,!fresh.flag(flag));member(p,who);});}back(menu,49);menu.open();
    }
    public void confirm(ServerPlayer player,String title,TeamsCore.Throwing action)throws Exception {Screen menu=new Screen(player,"confirm-gui",27,title);menu.button("confirm",11,"LIME_CONCRETE","&aConfirm",p->{p.closeContainer();action.run();});menu.button("cancel",15,"RED_CONCRETE","&cCancel",this::team);menu.open();}
    public void warps(ServerPlayer player)throws Exception {core.check(player,"warps");Team team=core.requireTeam(player);Screen menu=new Screen(player,"warps-gui",54,"Team warps");List<Map<String,Object>> warps=core.store.rows("team_warps","team_id",team.id());int slot=10;for(var warp:warps){if(slot>=45)break;String name=warp.get("warp_name").toString();menu.direct(slot++,"ENDER_PEARL",name,List.of(Objects.toString(warp.get("location"),"")),p->{String password=(String)warp.get("password");if(password!=null&&!password.isEmpty())core.prompt(p,"Warp password",text->core.safely(p,()->core.teleport(p,"warp",name,text)));else core.teleport(p,"warp",name,null);});}back(menu,49);menu.open();}
    public void upgrades(ServerPlayer player)throws Exception {
        core.check(player,"upgrade");Team team=core.requireTeam(player);Screen menu=new Screen(player,"upgrades-gui",45,"Team upgrades");menu.button("current-tier",11,"IRON_INGOT","&aTier "+team.tier(),null);
        int next=team.tier()+1;menu.button("next-tier",15,"GOLD_INGOT","&eTier "+next+" : "+Texts.money(core.settings.number("team_upgrades.tiers."+next+".cost",0)),null);
        menu.button("upgrade-button",22,"NETHER_STAR","&aUpgrade",p->confirm(p,"Upgrade",()->core.upgrade(p)));back(menu,40);menu.open();
    }
    public void top(ServerPlayer player,String category)throws Exception {
        core.check(player,"top");if(category==null){Screen menu=new Screen(player,"leaderboard-category-gui",27,"Top teams");menu.button("top-kills",11,"DIAMOND_SWORD","&aKills",p->top(p,"kills"));menu.button("top-balance",13,"GOLD_INGOT","&aBalance",p->top(p,"balance"));menu.button("top-members",15,"PLAYER_HEAD","&aMembers",p->top(p,"members"));back(menu,22);menu.open();return;}
        Screen menu=new Screen(player,"leaderboard-view-gui",54,"Top: "+category);int slot=10;for(Team team:core.store.top(category,30)){if(slot>=45)break;menu.direct(slot++,"PLAYER_HEAD",team.name()+" ["+team.tag()+"]",List.of("Bank "+Texts.money(team.balance()),"Members "+core.store.members(team.id()).size()),null);}back(menu,49);menu.open();
    }
    public void requests(ServerPlayer player)throws Exception {core.elevated(player);Team team=core.requireTeam(player);Screen menu=new Screen(player,"join-requests-gui",54,"Join requests");int slot=10;for(var row:core.store.rows("join_requests","team_id",team.id())){if(slot>=45)break;UUID who=UUID.fromString(row.get("player_uuid").toString());menu.direct(slot++,"PLAYER_HEAD",core.playerName(who),List.of("Accept"),p->{core.elevated(p);Team fresh=core.requireTeam(p);core.ensureChanged(core.store.acceptRequest(fresh.id(),p.getUUID(),who,core.capacity(fresh)));core.refresh(who);requests(p);});}back(menu,49);menu.open();}
    public void invites(ServerPlayer player)throws Exception {Screen menu=new Screen(player,"invites-gui",54,"Invites");int slot=10;for(var row:core.store.rows("team_invites","player_uuid",player.getUUID())){if(slot>=45)break;Team team=core.store.team(((Number)row.get("team_id")).intValue()).orElse(null);if(team!=null)menu.direct(slot++,"PAPER",team.name(),List.of("Accept"),p->core.accept(p,team.name()));}back(menu,49);menu.open();}
    public void allies(ServerPlayer player)throws Exception {core.check(player,"ally");Team team=core.requireTeam(player);Screen menu=new Screen(player,"allies-gui",54,"Allies");int slot=10;for(var row:core.store.rows("team_allies",null,null)){int a=((Number)row.get("team_id_1")).intValue(),b=((Number)row.get("team_id_2")).intValue();if(a!=team.id()&&b!=team.id())continue;Team ally=core.store.team(a==team.id()?b:a).orElse(null);if(ally!=null&&slot<45)menu.direct(slot++,"GREEN_BANNER",ally.name(),List.of(ally.tag()),null);}back(menu,49);menu.open();}
    public void blacklist(ServerPlayer player)throws Exception {core.elevated(player);Team team=core.requireTeam(player);Screen menu=new Screen(player,"blacklist-gui",54,"Blacklist");int slot=10;for(var row:core.store.rows("team_blacklist","team_id",team.id())){if(slot>=45)break;menu.direct(slot++,"BARRIER",row.get("player_name").toString(),List.of(Objects.toString(row.get("reason"),"")),p->{core.elevated(p);core.store.deleteRows("team_blacklist",Map.of("team_id",core.requireTeam(p).id(),"player_uuid",row.get("player_uuid")));blacklist(p);});}back(menu,49);menu.open();}
    public void quests(ServerPlayer player)throws Exception {if(!questEnabled())throw new TeamsCore.Refusal("QUESTS_DISABLED");throw new TeamsCore.Refusal("QUEST_CONFIGURATION_REQUIRES_MIGRATION");}
    private boolean questEnabled(){Object root=core.settings.quests().get("settings");return root instanceof Map<?,?> values&&Boolean.TRUE.equals(values.get("enabled"));}
    private void back(Screen screen,int slot)throws Exception{screen.button("back-button",slot,"ARROW","&7Back",this::team);}
    private String render(ServerPlayer player,String text)throws Exception {
        Team team=core.store.teamOf(player.getUUID()).orElse(null);Map<String,String> values=new LinkedHashMap<>();values.put("player",player.getGameProfile().getName());
        if(team!=null){values.put("team_name",team.name());values.put("team_tag",team.tag());values.put("team_description",Objects.toString(team.field("description"),""));values.put("members",Integer.toString(core.store.members(team.id()).size()));values.put("max_members",Integer.toString(core.capacity(team)));values.put("team_balance",Texts.money(team.balance()));values.put("balance",Texts.money(team.balance()));values.put("tier",Integer.toString(team.tier()));values.put("public_status",Boolean.toString(team.isPublic()));}
        for(var value:values.entrySet())text=text.replace("<"+value.getKey()+">",value.getValue());return text;
    }
    private Object value(String path){Object current=core.settings.gui();for(String part:path.split("\\.")){if(!(current instanceof Map<?,?> map))return null;current=map.get(part);}return current;}
    private String string(String path,String fallback){Object result=value(path);return result==null?fallback:result.toString();}
    private int number(String path,int fallback){Object result=value(path);return result instanceof Number number?number.intValue():fallback;}
    private boolean bool(String path,boolean fallback){Object result=value(path);return result instanceof Boolean bool?bool:fallback;}
    private static ItemStack item(String material,String name,List<String> lore){ResourceLocation key=ResourceLocation.tryParse(material.toLowerCase(Locale.ROOT));Item type=key!=null?BuiltInRegistries.ITEM.get(key):Items.BARRIER;if(type==Items.AIR)type=Items.BARRIER;ItemStack stack=new ItemStack(type);stack.set(DataComponents.CUSTOM_NAME,Texts.of(name));if(!lore.isEmpty())stack.set(DataComponents.LORE,new ItemLore(lore.stream().map(Texts::of).toList()));return stack;}
    private static MenuType<?> type(int rows){return switch(rows){case 1->MenuType.GENERIC_9x1;case 2->MenuType.GENERIC_9x2;case 3->MenuType.GENERIC_9x3;case 4->MenuType.GENERIC_9x4;case 5->MenuType.GENERIC_9x5;default->MenuType.GENERIC_9x6;};}
}
