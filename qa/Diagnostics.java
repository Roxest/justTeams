import java.nio.file.*;
import java.sql.*;
import java.util.*;
public final class Diagnostics {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();
        if(!Files.isRegularFile(root.resolve(".justteams-synthetic-fixture")))throw new IllegalArgumentException("fixture marker absent");
        try {
            Class<?> api=Class.forName("com.pixelclub.teams.Settings");
            Object settings=api.getConstructor(Path.class).newInstance(root.resolve("config/JustTeams"));
            api.getMethod("load").invoke(settings);System.out.println("Settings.load PASS");
        }catch(java.lang.reflect.InvocationTargetException error){error.getCause().printStackTrace();}
        Driver driver=(Driver)Class.forName("eu.kotori.justTeams.libs.h2.Driver").getConstructor().newInstance();
        Properties properties=new Properties();properties.setProperty("user","sa");properties.setProperty("password","");
        String url="jdbc:h2:file:"+root.resolve("config/JustTeams/data/teams-pxc").toString().replace('\\','/')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        try(Connection connection=driver.connect(url,properties);Statement query=connection.createStatement();ResultSet rows=query.executeQuery("SELECT * FROM `donut_teams` WHERE 1=0")){System.out.println("runtime H2 URL read PASS");}
        catch(Exception error){error.printStackTrace();}
    }
}
