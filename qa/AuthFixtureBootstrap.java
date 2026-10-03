import java.nio.file.*;
import java.sql.*;
import java.util.*;
public final class AuthFixtureBootstrap {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath().normalize();if(!Files.isRegularFile(root.resolve(".justteams-real-auth-fixture")))throw new IllegalArgumentException("auth marker absent");
        String secret=System.getenv("JTQA_NATIVE_AUTH_PASSWORD");if(secret==null||secret.length()<12)throw new IllegalArgumentException("synthetic secret env absent");
        Path base=root.resolve("config/pxc-auth-qa/accounts");Files.createDirectories(base.getParent());if(Files.exists(Path.of(base+".mv.db")))throw new IllegalArgumentException("fresh auth DB required");
        Driver driver=(Driver)Class.forName("eu.kotori.justTeams.libs.h2.Driver").getConstructor().newInstance();Properties props=new Properties();props.setProperty("user","sa");props.setProperty("password","");
        String url="jdbc:h2:file:"+base.toString().replace('\\','/')+";MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        try(Connection connection=driver.connect(url,props);Statement ddl=connection.createStatement()){
            ddl.execute("CREATE TABLE authme(id INT AUTO_INCREMENT PRIMARY KEY,username VARCHAR(255) NOT NULL UNIQUE,realname VARCHAR(255) NOT NULL,password VARCHAR(255) NOT NULL,ip VARCHAR(40),lastlogin BIGINT,x DOUBLE NOT NULL DEFAULT 0,y DOUBLE NOT NULL DEFAULT 99,z DOUBLE NOT NULL DEFAULT 0,world VARCHAR(255) NOT NULL DEFAULT 'world',regdate BIGINT NOT NULL DEFAULT 0,regip VARCHAR(40),yaw FLOAT,pitch FLOAT,email VARCHAR(255),isLogged SMALLINT NOT NULL DEFAULT 0,hasSession SMALLINT NOT NULL DEFAULT 0,totp VARCHAR(32))");
            String hash=(String)Class.forName("com.pixelclub.auth.nativeauth.AuthMeHash").getMethod("hash",String.class).invoke(null,secret);
            try(PreparedStatement insert=connection.prepareStatement("INSERT INTO authme(username,realname,password,regdate,regip) VALUES(?,?,?,?,?)")){insert.setString(1,"jtmoneyowner");insert.setString(2,"JTMoneyOwner");insert.setString(3,hash);insert.setLong(4,System.currentTimeMillis());insert.setString(5,"127.0.0.1");insert.executeUpdate();}
        }
        System.out.println("Separate local auth H2 seeded: one synthetic hashed account; no secret output");
    }
}
