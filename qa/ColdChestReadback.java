import java.nio.file.*;
import java.sql.*;
import java.util.*;
public final class ColdChestReadback {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();if(!Files.isRegularFile(root.resolve(".justteams-real-auth-fixture")))throw new IllegalArgumentException("marked local auth fixture required");
        Driver driver=(Driver)Class.forName("eu.kotori.justTeams.libs.h2.Driver").getConstructor().newInstance();Properties p=new Properties();p.setProperty("user","sa");p.setProperty("password","");
        try(Connection c=driver.connect("jdbc:h2:file:"+root.resolve("config/JustTeams/data/teams-pxc").toString().replace('\\','/')+";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",p);Statement s=c.createStatement()){
            try(ResultSet rs=s.executeQuery("SELECT inventory_json,generation,original_sha256 FROM pxc_teams_chests WHERE team_id=1")){
                if(!rs.next()||!rs.getString(1).equals(Files.readString(Path.of(args[1])))||rs.getLong(2)!=Long.parseLong(args[2])||!Objects.equals(rs.getString(3),args[3].equals("null")?null:args[3]))throw new IllegalStateException("cold chest exact JSON/generation/source mismatch");
            }
            try(ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM pxc_teams_operations WHERE kind NOT IN ('INVITE','IDENTITY') AND phase NOT IN ('DONE','CANCELLED','COMPENSATED')")){rs.next();if(rs.getInt(1)!=0)throw new IllegalStateException("cold unresolved operation");}
        }
        System.out.println("Cold local H2 exact chest JSON/generation/source verified; pending0; no wallet/auth secret read");
    }
}
