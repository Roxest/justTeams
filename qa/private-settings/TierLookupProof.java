import com.pixelclub.teams.Settings;
import java.nio.file.Path;
import java.nio.file.Files;

/** Synthetic shape only, same unquoted integer tier key type observed by safe donor projection. */
public final class TierLookupProof {
    public static void main(String[] args) throws Exception {
        String fixture = "team_upgrades:\n  enabled: true\n  tiers:\n    2:\n      cost: 250000\n";
        Path folder = Files.createTempDirectory("justteams-synthetic-settings-");
        Files.writeString(folder.resolve("config.yml"), "team_bank:\n  max_balance: 1000000000\n" + fixture);
        for (String name : new String[]{"messages.yml", "gui.yml", "quests.yml", "commands.yml", "placeholders.yml"}) Files.writeString(folder.resolve(name), "{}\n");
        Settings settings = new Settings(folder); settings.load();
        System.out.println("native_string_lookup_cost=" + settings.value("team_upgrades.tiers.2.cost"));
        System.out.println("synthetic_expected_cost=250000");
    }
}
