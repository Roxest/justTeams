package com.pixelclub.teams;

import com.pixelclub.placeholders.api.PxcPlaceholders;
import com.pixelclub.placeholders.api.PlaceholderSubject;
import com.pixelclub.teams.store.*;
import java.util.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import net.minecraft.ChatFormatting;
import java.util.regex.*;
import java.text.SimpleDateFormat;

/** Same legacy identifier registered on Pxc's native engine; unknown fields remain unresolved. */
public final class TeamPlaceholders {
    private TeamPlaceholders(){}
    private record Ranking(long expires,List<Team> teams){}
    private static final Map<TeamsCore,Map<String,Ranking>> rankings=new WeakHashMap<>();
    public static void register(TeamsCore core){PxcPlaceholders.register("justteams","justteams",(subject,params)->resolve(core,subject,params));}
    public static String resolve(TeamsCore core,PlaceholderSubject subject,String params) {
        try {
            if(subject==null||subject.uuid()==null)return "";
            if(!core.snapshots.healthy())return null;
            String key=params.toLowerCase(Locale.ROOT);var snapshot=core.snapshots.subject(subject.uuid());Team team=snapshot.team();
            if(key.startsWith("top_")||key.startsWith("leaderboard_"))return top(core,key);
            if(!core.settings.enabled("team_tag")&&Set.of("tag_raw","tag","team_tag","tag_silent","colored_tag","color_tag","gradient_tag","plain_tag").contains(key))return "";
            if(key.equals("has_team"))return Boolean.toString(team!=null);
            String name=Objects.toString(subject.name(),"").toLowerCase(Locale.ROOT);
            if(key.equals("tab_sort")||key.equals("sort_key")){if(team==null)return "9999999999_3_"+name;Member member=snapshot.member();int role=member!=null&&member.owner()?0:member!=null&&member.role().equals("CO_OWNER")?1:2;return String.format(Locale.ROOT,"%010d_%d_%s",team.id(),role,name);}
            if(team==null)return switch(key){case "display"->core.settings.placeholder("team_display.no_team","");case "tag_raw","tag_silent","colored_tag","color_tag","gradient_tag"->"";default->core.settings.message("no_team_placeholder","No Team");};
            List<Member> members=snapshot.members();Member member=snapshot.member();
            return switch(key){
                case "name","team","team_name","color_name"->team.name();
                case "plain_name"->plain(team.name());
                case "tag_raw"->team.tag();
                case "plain_tag"->core.settings.enabled("team_tag")?plain(team.tag()):"";
                case "tag","team_tag","tag_silent"->legacyMini(team.tag());
                case "colored_tag","color_tag","gradient_tag"->colored(team);
                case "display"->display(core,team);
                case "team_color"->color(team).toUpperCase(Locale.ROOT);
                case "team_color_code","color_code"->colorCode(team);
                case "description","team_description"->Objects.toString(team.field("description"),"");
                case "team_id"->Integer.toString(team.id());
                case "owner","team_owner","leader"->core.playerName(team.owner());
                case "tier"->Integer.toString(team.tier());
                case "size","team_size","member_count","team_members"->Integer.toString(members.size());
                case "max_members","team_max_members","team_capacity","team_max_size","max_size"->Integer.toString(core.capacity(team));
                case "team_full"->Boolean.toString(members.size()>=core.capacity(team));
                case "members_online","online","online_members"->Long.toString(members.stream().filter(m->core.server.getPlayerList().getPlayer(m.uuid())!=null).count());
                case "offline_members"->Long.toString(members.stream().filter(m->core.server.getPlayerList().getPlayer(m.uuid())==null).count());
                case "balance","team_balance","bank_balance"->currency(core,team.balance());
                case "bank_balance_raw"->Double.toString(team.balance());
                case "bank_formatted"->"$"+currency(core,team.balance());
                case "points","team_points"->Objects.toString(team.field("points"),"0");
                case "kills","team_kills"->Objects.toString(team.field("kills"),"0");
                case "deaths","team_deaths"->Objects.toString(team.field("deaths"),"0");
                case "kd","kdr","team_kdr"->shortNumber(number(team,"deaths")==0?number(team,"kills"):number(team,"kills")/number(team,"deaths"));
                case "role"->member==null?"":member.role();
                case "role_level"->member==null?"1":member.owner()?"4":member.role().equals("CO_OWNER")?"3":"1";
                case "role_icon"->core.settings.placeholder("roles."+roleKey(member)+".icon",member!=null&&member.owner()?"★":member!=null&&member.role().equals("CO_OWNER")?"◆":"●");
                case "role_color"->core.settings.placeholder("roles."+roleKey(member)+".color",member!=null&&member.owner()?"#FFD700":member!=null&&member.role().equals("CO_OWNER")?"#FF6B6B":"#FFFFFF");
                case "is_owner"->Boolean.toString(member!=null&&member.owner());
                case "is_co_owner"->Boolean.toString(member!=null&&member.role().equals("CO_OWNER"));
                case "is_admin"->Boolean.toString(member!=null&&Access.elevated(member));
                case "is_member"->Boolean.toString(member!=null);
                case "is_public"->Boolean.toString(team.isPublic());
                case "team_public"->team.isPublic()?"Public":"Private";
                case "team_pvp","pvp_status"->team.pvp()?"Enabled":"Disabled";
                case "pvp_enabled"->Boolean.toString(team.pvp());
                case "pvp_toggle_cooldown"->core.pvpCooldown(team.id())>0?core.pvpCooldown(team.id())+"s":"Ready";
                case "has_home"->Boolean.toString(team.location()!=null&&!team.location().isEmpty());
                case "home_location"->home(core,team.location());
                case "warp_count"->Integer.toString(core.store.rows("team_warps","team_id",team.id()).size());
                case "max_warps"->Integer.toString(core.maxWarps(team));
                case "join_date"->date(member==null?null:member.field("join_date"));
                case "created_at"->date(team.field("created_at"));
                default->null;
            };
        }catch(Exception e){return null;}
    }
    private static String roleKey(Member member){return member!=null&&member.owner()?"owner":member!=null&&member.role().equals("CO_OWNER")?"co_owner":"member";}
    private static double number(Team team,String key){Object value=team.field(key);return value instanceof Number number?number.doubleValue():0;}
    private static String shortNumber(double value){return new DecimalFormat("#.##",DecimalFormatSymbols.getInstance(Locale.US)).format(value);}
    private static String currency(TeamsCore core,double value){return new DecimalFormat(core.settings.text("settings.currency_format","#,##0.00"),DecimalFormatSymbols.getInstance(Locale.US)).format(value);}
    private static String plain(String value){return value.replaceAll("(?i)(?:§|&)[0-9a-fk-orx]","").replaceAll("<[^>]*>","");}
    private static String date(Object value){return value instanceof Date date?new SimpleDateFormat("dd MMM yyyy").format(date):"Unknown";}
    private static String home(TeamsCore core,String location){if(location==null||location.isEmpty())return "No home set";try{String[] fields=location.split(",",-1);if(fields.length!=6)return "No home set";var level=core.resolveWorld(fields[0]);if(level==null)return "No home set";return core.worldName(level)+", "+(int)Math.floor(Double.parseDouble(fields[1]))+", "+(int)Math.floor(Double.parseDouble(fields[2]))+", "+(int)Math.floor(Double.parseDouble(fields[3]));}catch(RuntimeException e){return "No home set";}}
    private static String color(Team team){String value=Objects.toString(team.field("color"),"white").replace("<","").replace(">","");return value.isEmpty()?"white":value;}
    private static String colorCode(Team team){ChatFormatting format=ChatFormatting.getByName(color(team).toLowerCase(Locale.ROOT));return format==null?"§f":format.toString();}
    private static String display(TeamsCore core,Team team){String color=core.settings.placeholder("team_display.team_color","#4C9DDE"),tagColor=core.settings.placeholder("team_display.tag_color","#FFD700");String tag=core.settings.placeholder("team_display.show_tag","true").equals("true")&&core.settings.enabled("team_tag")?"<"+tagColor+">"+core.settings.placeholder("team_display.tag_prefix","[")+team.tag()+core.settings.placeholder("team_display.tag_suffix","]")+"</"+tagColor+">":"";String icon=core.settings.placeholder("team_display.show_icon","true").equals("true")?core.settings.placeholder("team_display.team_icon","⚔ "):"";return core.settings.placeholder("team_display.format","<team_color><team_icon><team_tag></team_color>").replace("<team_color>","<"+color+">").replace("</team_color>","</"+color+">").replace("<team_name>",team.name()).replace("%team_name%",team.name()).replace("<team_tag>",tag).replace("%team_tag%",tag).replace("<team_icon>",icon).replace("%team_icon%",icon);}
    private static String legacyMini(String value){return Texts.tagMarkup(value);}
    private static String colored(Team team){String start=Objects.toString(team.field("gradient_start"),""),end=Objects.toString(team.field("gradient_end"),"");if(start.matches("#[0-9a-fA-F]{6}")&&end.matches("#[0-9a-fA-F]{6}"))return "<gradient:"+start+":"+end+">"+Texts.tagMarkup(team.tag())+"</gradient>";String color=Objects.toString(team.field("color"),"").replace("<","").replace(">","");ChatFormatting format=ChatFormatting.getByName(color.toLowerCase(Locale.ROOT));String prefix=format!=null?format.toString():color.startsWith("#")?"&#"+color.substring(1):color;return prefix+Texts.tagMarkup(team.tag())+"§r";}
    private static String top(TeamsCore core,String key)throws Exception {
        String category=core.settings.text("settings.top_default_metric","kills"),type;int position;boolean legacy=false;
        Matcher explicit=Pattern.compile("(?:top|leaderboard)_(kills|balance|members|points)_([0-9]+)_(name|tag|colored_tag|gradient_tag|amount|value)").matcher(key);
        Matcher old=Pattern.compile("(?:top|leaderboard)_(name|tag|colored_tag|gradient_tag|amount|value)_([0-9]+)").matcher(key);
        Matcher repaired=Pattern.compile("(?:top|leaderboard)_(kills|balance|members|points)_(amount|value)_([0-9]+)").matcher(key);
        if(explicit.matches()){category=explicit.group(1);position=Integer.parseInt(explicit.group(2));type=explicit.group(3);}
        else if(old.matches()){type=old.group(1);position=Integer.parseInt(old.group(2));legacy=true;}
        else if(repaired.matches()){category=repaired.group(1);type=repaired.group(2);position=Integer.parseInt(repaired.group(3));}else return "---";
        if(position<1||position>10)return "---";Map<String,Ranking> cache=rankings.computeIfAbsent(core,c->new HashMap<>());Ranking ranking=cache.get(category);long now=System.currentTimeMillis();if(ranking==null||ranking.expires()<now){ranking=new Ranking(now+60000,List.copyOf(core.store.top(category,10)));cache.put(category,ranking);}if(ranking.teams().size()<position)return "---";Team team=ranking.teams().get(position-1);String metric=legacy?"kills":category;
        return switch(type){case "name"->team.name();case "tag"->team.tag();case "colored_tag","gradient_tag"->core.settings.enabled("team_tag")?colored(team):"";case "amount","value"->metric.equals("balance")?shortNumber(team.balance()):Objects.toString(team.field(metric.equals("members")?"member_count":metric),"0");default->"---";};
    }
}
