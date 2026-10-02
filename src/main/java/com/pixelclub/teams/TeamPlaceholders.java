package com.pixelclub.teams;

import com.pixelclub.placeholders.api.PxcPlaceholders;
import com.pixelclub.placeholders.api.PlaceholderSubject;
import com.pixelclub.teams.store.*;
import java.util.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import net.minecraft.ChatFormatting;

/** Same legacy identifier registered on Pxc's native engine; unknown fields remain unresolved. */
public final class TeamPlaceholders {
    private TeamPlaceholders(){}
    public static void register(TeamsCore core){PxcPlaceholders.register("justteams","justteams",(subject,params)->resolve(core,subject,params));}
    public static String resolve(TeamsCore core,PlaceholderSubject subject,String params) {
        try {
            String key=params.toLowerCase(Locale.ROOT);Team team=subject.uuid()==null?null:core.store.teamOf(subject.uuid()).orElse(null);
            if(key.startsWith("top_")||key.startsWith("leaderboard_"))return top(core,key);
            if(key.equals("has_team"))return Boolean.toString(team!=null);
            String name=Objects.toString(subject.name(),"").toLowerCase(Locale.ROOT);
            if(key.equals("tab_sort")||key.equals("sort_key")){if(team==null)return "9999999999_3_"+name;Member member=core.store.member(subject.uuid()).orElse(null);int role=member!=null&&member.owner()?0:member!=null&&member.role().equals("CO_OWNER")?1:2;return String.format(Locale.ROOT,"%010d_%d_%s",team.id(),role,name);}
            if(team==null)return switch(key){case "display"->core.settings.placeholder("team_display.no_team","");case "tag_raw","tag_silent","colored_tag","gradient_tag"->"";default->core.settings.message("no_team_placeholder","No Team");};
            List<Member> members=core.store.members(team.id());Member member=subject.uuid()==null?null:core.store.member(subject.uuid()).orElse(null);
            return switch(key){
                case "name","team_name","plain_name"->team.name();
                case "tag_raw","plain_tag"->team.tag();
                case "tag","team_tag","tag_silent"->legacyMini(team.tag());
                case "colored_tag","gradient_tag","display"->colored(team);
                case "team_id"->Integer.toString(team.id());
                case "owner"->core.playerName(team.owner());
                case "tier"->Integer.toString(team.tier());
                case "size","member_count"->Integer.toString(members.size());
                case "max_members","team_capacity"->Integer.toString(core.capacity(team));
                case "team_full"->Boolean.toString(members.size()>=core.capacity(team));
                case "members_online"->Long.toString(members.stream().filter(m->core.server.getPlayerList().getPlayer(m.uuid())!=null).count());
                case "balance","team_balance","bank_balance"->currency(core,team.balance());
                case "bank_balance_raw"->Double.toString(team.balance());
                case "bank_formatted"->"$"+currency(core,team.balance());
                case "points","team_points"->Objects.toString(team.field("points"),"0");
                case "kills","deaths"->Objects.toString(team.field(key),"0");
                case "kdr"->shortNumber(number(team,"deaths")==0?number(team,"kills"):number(team,"kills")/number(team,"deaths"));
                case "role"->member==null?"":member.role();
                case "role_level"->member==null?"1":member.owner()?"4":member.role().equals("CO_OWNER")?"3":"1";
                case "role_icon"->core.settings.placeholder("roles."+roleKey(member)+".icon",member!=null&&member.owner()?"★":member!=null&&member.role().equals("CO_OWNER")?"◆":"●");
                case "role_color"->core.settings.placeholder("roles."+roleKey(member)+".color",member!=null&&member.owner()?"#FFD700":member!=null&&member.role().equals("CO_OWNER")?"#FF6B6B":"#FFFFFF");
                case "is_public"->Boolean.toString(team.isPublic());
                case "pvp_enabled"->Boolean.toString(team.pvp());
                case "has_home"->Boolean.toString(team.location()!=null&&!team.location().isEmpty());
                case "home_location"->Objects.toString(team.location(),"");
                case "warp_count"->Integer.toString(core.store.rows("team_warps","team_id",team.id()).size());
                case "max_warps"->Integer.toString(core.maxWarps(team));
                case "join_date"->member==null?"":Objects.toString(member.field("join_date"),"");
                case "created_at"->Objects.toString(team.field("created_at"),"");
                default->null;
            };
        }catch(Exception e){return null;}
    }
    private static String roleKey(Member member){return member!=null&&member.owner()?"owner":member!=null&&member.role().equals("CO_OWNER")?"co_owner":"member";}
    private static double number(Team team,String key){Object value=team.field(key);return value instanceof Number number?number.doubleValue():0;}
    private static String shortNumber(double value){return new DecimalFormat("#.##",DecimalFormatSymbols.getInstance(Locale.US)).format(value);}
    private static String currency(TeamsCore core,double value){return new DecimalFormat(core.settings.text("settings.currency_format","#,##0.00"),DecimalFormatSymbols.getInstance(Locale.US)).format(value);}
    private static String legacyMini(String value){return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(value.replace('§','&')));}
    private static String colored(Team team){String start=Objects.toString(team.field("gradient_start"),""),end=Objects.toString(team.field("gradient_end"),"");if(start.matches("#[0-9a-fA-F]{6}")&&end.matches("#[0-9a-fA-F]{6}"))return "<gradient:"+start+":"+end+">"+team.tag()+"</gradient>";String color=Objects.toString(team.field("color"),"").replace("<","").replace(">","");ChatFormatting format=ChatFormatting.getByName(color.toLowerCase(Locale.ROOT));String prefix=format!=null?format.toString():color.startsWith("#")?"&#"+color.substring(1):color;return prefix+team.tag()+"§r";}
    private static String top(TeamsCore core,String key)throws Exception {
        String[] tokens=key.split("_");int offset=key.startsWith("leaderboard_")?1:1;String category=core.settings.text("settings.top_default_metric","kills"),type="name";int position;
        if(tokens.length>=4&&Set.of("kills","balance","members","points").contains(tokens[offset])){category=tokens[offset];position=Integer.parseInt(tokens[offset+1]);type=tokens[offset+2];}
        else if(tokens.length>=3){type=tokens[offset];position=Integer.parseInt(tokens[offset+1]);}
        else return null;
        if(position<1||position>1000)return null;List<Team> teams=core.store.top(category,position);if(teams.size()<position)return "";Team team=teams.get(position-1);
        return switch(type){case "name"->team.name();case "tag"->team.tag();case "colored","colored_tag","gradient_tag"->colored(team);case "amount","value"->shortNumber(category.equals("members")?number(team,"member_count"):number(team,category));default->null;};
    }
}
