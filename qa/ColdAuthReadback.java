import java.nio.file.*;
import java.sql.*;
import java.util.*;
public final class ColdAuthReadback {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();if(!Files.isRegularFile(root.resolve(".justteams-real-auth-fixture")))throw new IllegalArgumentException("marked local auth root required");
        Driver driver=(Driver)Class.forName("eu.kotori.justTeams.libs.h2.Driver").getConstructor().newInstance();Properties p=new Properties();p.setProperty("user","sa");p.setProperty("password","");
        try(Connection c=driver.connect("jdbc:h2:file:"+root.resolve("config/JustTeams/data/teams-pxc").toString().replace('\\','/')+";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",p);Statement s=c.createStatement()){
            try(ResultSet rs=s.executeQuery("SELECT balance,tier,is_public FROM donut_teams WHERE id=1")){if(!rs.next()||rs.getDouble(1)!=750000||rs.getInt(2)!=2)throw new IllegalStateException("cold tier/bank mismatch");}
            try(ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM pxc_teams_operations WHERE kind NOT IN ('INVITE','IDENTITY') AND phase NOT IN ('DONE','CANCELLED','COMPENSATED')")){rs.next();if(rs.getInt(1)!=0)throw new IllegalStateException("cold unresolved operation");}
        }
        System.out.println("Cold local H2 verified: tier2 bank750000 pending0; no wallet/auth secret read");
    }
}
