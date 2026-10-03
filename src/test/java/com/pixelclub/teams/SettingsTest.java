package com.pixelclub.teams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @TempDir Path folder;
    private Settings load(String config) throws Exception {
        Files.writeString(folder.resolve("config.yml"),config);
        for(String name:new String[]{"messages.yml","gui.yml","quests.yml","placeholders.yml","commands.yml"})Files.writeString(folder.resolve(name),"{}\n");
        Settings settings=new Settings(folder);settings.load();return settings;
    }
    @Test void integerYamlTierKeysResolveExactConfiguredCostAndCapacity() throws Exception {
        Settings settings=load("team_bank: {max_balance: 1000000000}\nteam_upgrades:\n  tiers:\n    2: {cost: 250000, max_members: 12, max_warps: 6}\n    '3': {cost: 500000, max_members: 15}\n");
        assertNotNull(settings.value("team_upgrades.tiers.2"));
        assertEquals(250000,settings.number("team_upgrades.tiers.2.cost",-1));
        assertEquals(12,settings.integer("team_upgrades.tiers.2.max_members",-1));
        assertEquals(6,settings.integer("team_upgrades.tiers.2.max_warps",-1));
        assertEquals(500000,settings.number("team_upgrades.tiers.3.cost",-1));
        assertNull(settings.value("team_upgrades.tiers.4"));
    }
    @Test void malformedReloadKeepsAllPreviouslyPublishedSettings() throws Exception {
        Settings settings=load("team_bank: {max_balance: 1000}\nfeature_costs: {economy: {rename: 50}}\n");
        Files.writeString(folder.resolve("config.yml"),"team_bank: {max_balance: 2000}\nfeature_costs: {economy: {rename: 75}}\n");
        Files.writeString(folder.resolve("messages.yml"),"[invalid root]\n");
        assertThrows(IOException.class,settings::load);
        assertEquals(1000,settings.number("team_bank.max_balance",-1));
        assertEquals(50,settings.number("feature_costs.economy.rename",-1));
    }
    @Test void finiteUncappedReloadAcceptedAndNonfiniteReloadRetainsPreviousState() throws Exception {
        Settings settings=load("team_bank: {max_balance: -1}\n");
        assertEquals(-1,settings.number("team_bank.max_balance",99));
        Files.writeString(folder.resolve("config.yml"),"team_bank: {max_balance: 0}\n");
        settings.load();assertEquals(0,settings.number("team_bank.max_balance",99));
        Files.writeString(folder.resolve("config.yml"),"team_bank: {max_balance: .nan}\n");
        assertThrows(IOException.class,settings::load);
        assertEquals(0,settings.number("team_bank.max_balance",99));
    }
}
