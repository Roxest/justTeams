package com.pixelclub.teams;

import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Donor upgrade presentation; prices and benefits come from the same configured tiers. */
final class UpgradeView {
    private UpgradeView() {}
    static int maximum(Settings settings){int highest=1;Object tiers=settings.value("team_upgrades.tiers");if(tiers instanceof Map<?,?> map)for(Object key:map.keySet())try{highest=Math.max(highest,Integer.parseInt(key.toString()));}catch(NumberFormatException ignored){}return Math.min(highest,10);}
    static boolean available(Settings settings,int tier){return settings.bool("team_upgrades.enabled",true)&&tier<maximum(settings);}
    private static int benefit(Settings settings,int tier,String field,int fallback,int limit){int clamped=Math.max(1,Math.min(tier,maximum(settings)));int value=settings.integer("team_upgrades.tiers."+clamped+"."+field,fallback);return Math.max(field.endsWith("percent")?0:1,Math.min(limit,value));}
    static Map<String,String> values(Settings settings,int tier,double balance){
        int max=maximum(settings),next=tier+1;boolean hasNext=next<=max;
        DecimalFormat formatter=new DecimalFormat(settings.text("settings.currency_format","#,##0.00"));
        Map<String,String> result=new LinkedHashMap<>();
        result.put("current_tier",Integer.toString(tier));result.put("max_tier",Integer.toString(max));result.put("next_tier",hasNext?Integer.toString(next):"MAX");
        for(String prefix:new String[]{"current","next"}){
            int selected=prefix.equals("current")?tier:next;boolean visible=prefix.equals("current")||hasNext;
            result.put(prefix+"_max_members",visible?Integer.toString(benefit(settings,selected,"max_members",settings.integer("settings.max_team_size",10),100)):"-");
            result.put(prefix+"_enderchest_rows",visible?Integer.toString(benefit(settings,selected,"enderchest_rows",settings.integer("team_enderchest.rows",3),6)):"-");
            result.put(prefix+"_damage_bonus",visible?benefit(settings,selected,"damage_bonus_percent",0,15)+"%":"-");
            result.put(prefix+"_home_reduction",visible?benefit(settings,selected,"home_cooldown_reduction_percent",0,75)+"%":"-");
        }
        double cost=hasNext?settings.number("team_upgrades.tiers."+next+".cost",0):0;
        if(!Double.isFinite(cost))throw new IllegalArgumentException("Invalid upgrade display cost");
        result.put("upgrade_cost",hasNext?formatter.format(Math.max(0,cost)):"-");result.put("team_balance",formatter.format(balance));
        return result;
    }
}
