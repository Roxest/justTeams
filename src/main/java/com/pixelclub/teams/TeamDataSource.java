package com.pixelclub.teams;

import com.pixelclub.teams.store.ConnectionFactory;
import eu.kotori.justTeams.libs.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.*;

public final class TeamDataSource implements ConnectionFactory,AutoCloseable {
    private final HikariDataSource pool;
    public TeamDataSource(Settings settings) {
        pool=new HikariDataSource();String type=settings.first("h2","storage.type").toLowerCase(java.util.Locale.ROOT);
        pool.setPoolName("pxc-justteams");pool.setMaximumPoolSize(4);pool.setConnectionTimeout(5000);pool.setInitializationFailTimeout(5000);
        if(type.equals("mysql")) {
            String host=settings.first("localhost","storage.mysql.host","mysql.host");
            String database=settings.first("","storage.mysql.database","storage.mysql.name","mysql.database");
            if(!host.matches("[A-Za-z0-9.:-]+")||!database.matches("[A-Za-z0-9_]+"))throw new IllegalArgumentException("invalid database endpoint");
            int port=settings.integer("storage.mysql.port",3306);
            if(port<1||port>65535)throw new IllegalArgumentException("invalid database port");
            pool.setJdbcUrl("jdbc:mysql://"+host+":"+port+"/"+database+"?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC");
            pool.setDriverClassName("com.mysql.cj.jdbc.Driver");
            pool.setUsername(settings.first("","storage.mysql.username","storage.mysql.user","mysql.username"));
            pool.setPassword(settings.first("","storage.mysql.password","mysql.password"));
        } else if(type.equals("h2")) {
            Path path=settings.folder().resolve("data/teams-pxc").toAbsolutePath().normalize();
            pool.setJdbcUrl("jdbc:h2:file:"+path+";MODE=MySQL;AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE");
            pool.setDriverClassName("eu.kotori.justTeams.libs.h2.Driver");pool.setUsername("sa");pool.setPassword("");
        } else throw new IllegalArgumentException("unsupported team storage backend");
    }
    @Override public Connection open()throws SQLException{return pool.getConnection();}
    @Override public void close(){pool.close();}
}
