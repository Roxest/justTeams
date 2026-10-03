package com.pixelclub.teams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class UpgradeViewTest {
    @TempDir Path folder;
    private Settings settings(String extra)throws Exception{
        Files.writeString(folder.resolve("config.yml"),"team_bank: {max_balance: 1000000000}\nsettings: {max_team_size: 10, currency_format: '#,##0.00'}\nteam_enderchest: {rows: 3}\nteam_upgrades:\n  enabled: true\n  tiers:\n    1: {max_members: 10, enderchest_rows: 3}\n    2: {max_members: 12, enderchest_rows: 4, damage_bonus_percent: 1, home_cooldown_reduction_percent: 10, cost: 250000}\n    3: {max_members: 15, enderchest_rows: 5, damage_bonus_percent: 2, home_cooldown_reduction_percent: 20, cost: 500000}\n"+extra);
        for(String name:new String[]{"gui.yml","messages.yml","quests.yml","placeholders.yml","commands.yml"})Files.writeString(folder.resolve(name),"{}\n");
        Settings settings=new Settings(folder);settings.load();return settings;
    }
    @Test void integerTierCostAndEveryConfiguredTokenResolve()throws Exception{
        var values=UpgradeView.values(settings(""),2,123.005);
        assertEquals("2",values.get("current_tier"));assertEquals("3",values.get("max_tier"));assertEquals("3",values.get("next_tier"));
        assertEquals("12",values.get("current_max_members"));assertEquals("15",values.get("next_max_members"));
        assertEquals("4",values.get("current_enderchest_rows"));assertEquals("5",values.get("next_enderchest_rows"));
        assertEquals("1%",values.get("current_damage_bonus"));assertEquals("2%",values.get("next_damage_bonus"));
        assertEquals("10%",values.get("current_home_reduction"));assertEquals("20%",values.get("next_home_reduction"));
        assertEquals("500,000.00",values.get("upgrade_cost"));assertEquals(13,values.size());
    }
    @Test void maximumTierDoesNotAdvertisePurchaseOrUndefinedBenefits()throws Exception{
        var settings=settings("");var values=UpgradeView.values(settings,3,0);
        assertFalse(UpgradeView.available(settings,3));assertEquals("MAX",values.get("next_tier"));
        for(String key:new String[]{"next_max_members","next_enderchest_rows","next_damage_bonus","next_home_reduction","upgrade_cost"})assertEquals("-",values.get(key));
    }
    @Test void configuredBenefitsClampAndEmptyCurrencyPatternIsHonoured()throws Exception{
        var settings=settings("    4: {max_members: 1000, enderchest_rows: 99, damage_bonus_percent: 99, home_cooldown_reduction_percent: 99, cost: -1}\n");
        var values=UpgradeView.values(settings,3,0);
        assertEquals("100",values.get("next_max_members"));assertEquals("6",values.get("next_enderchest_rows"));assertEquals("15%",values.get("next_damage_bonus"));assertEquals("75%",values.get("next_home_reduction"));assertEquals("0.00",values.get("upgrade_cost"));
    }
}
