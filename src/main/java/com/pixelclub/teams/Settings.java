package com.pixelclub.teams;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Reads the donor's YAML without moving secrets into logs or generating a different economy. */
public final class Settings {
    private final Path folder;
    private Map<String,Object> config = Map.of(), messages = Map.of(), gui = Map.of(), quests = Map.of(), placeholders=Map.of(),commands=Map.of();
    public Settings(Path folder) { this.folder = folder; }
    public synchronized void load() throws IOException {
        Files.createDirectories(folder);
        for (String name : List.of("config.yml", "messages.yml", "gui.yml", "quests.yml", "placeholders.yml", "commands.yml")) {
            Path target = folder.resolve(name);
            if (!Files.exists(target)) {
                try (InputStream in = Settings.class.getClassLoader().getResourceAsStream(name)) {
                    if (in == null) throw new IOException("missing bundled " + name);
                    Files.copy(in, target);
                }
            }
        }
        config = read(folder.resolve("config.yml"));
        messages = read(folder.resolve("messages.yml"));
        gui = read(folder.resolve("gui.yml"));
        quests = read(folder.resolve("quests.yml"));
        placeholders = read(folder.resolve("placeholders.yml"));
        commands = read(folder.resolve("commands.yml"));
        if (!Double.isFinite(number("team_bank.max_balance", -1)) || number("team_bank.max_balance", -1) <= 0)
            throw new IOException("team_bank.max_balance must be explicitly positive");
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> read(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        // Donor message/layout bundles contain duplicate keys and use the final value. Connection config remains strict.
        options.setAllowDuplicateKeys(!file.getFileName().toString().equals("config.yml"));
        options.setMaxAliasesForCollections(50); options.setCodePointLimit(4_000_000);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object value = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(value instanceof Map<?,?>)) throw new IOException("YAML root must be a mapping: " + file.getFileName());
            return Collections.unmodifiableMap((Map<String,Object>)value);
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("YAML rejected in " + file.getFileName() + " (" + e.getClass().getSimpleName() + ")"); }
    }
    public Object value(String key) { return lookup(config, key); }
    public String text(String key, String fallback) { Object v=value(key); return v==null?fallback:String.valueOf(v); }
    public String first(String fallback, String... keys) { for(String key:keys) if(value(key)!=null) return text(key,fallback); return fallback; }
    public double number(String key,double fallback) {
        Object v=value(key); if(v==null)return fallback;
        try { return v instanceof Number n?n.doubleValue():Double.parseDouble(v.toString()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("invalid numeric setting: " + key); }
    }
    public int integer(String key,int fallback) { double v=number(key,fallback); if(!Double.isFinite(v)||v!=Math.rint(v))throw new IllegalArgumentException("invalid integer setting: "+key);return Math.toIntExact((long)v); }
    public boolean bool(String key,boolean fallback) { Object v=value(key); if(v==null)return fallback; if(v instanceof Boolean b)return b; if("true".equalsIgnoreCase(v.toString()))return true; if("false".equalsIgnoreCase(v.toString()))return false; throw new IllegalArgumentException("invalid boolean setting: "+key); }
    public double cost(String feature) {
        if(!bool("feature_costs.enabled",true)||!bool("feature_costs.economy.enabled",true))return 0;
        double v=number("feature_costs.economy."+feature,0);
        if(!Double.isFinite(v)||v<0)throw new IllegalArgumentException("invalid feature cost: "+feature);return v;
    }
    public boolean itemCostEnabled() { return bool("feature_costs.enabled",true)&&bool("feature_costs.items.enabled",false); }
    public boolean enabled(String feature) { return bool("features."+feature,true); }
    public String message(String key,String fallback) { Object v=lookup(messages,key); return v==null?fallback:v.toString(); }
    public Map<String,Object> gui() { return gui; }
    public Map<String,Object> quests() { return quests; }
    public String command(String key,String fallback){Object value=lookup(commands,key);return value==null?fallback:value.toString();}
    public boolean commandAlias(String alias){return command("command-aliases."+alias+".enabled",alias.startsWith("party")?"false":"true").equalsIgnoreCase("true");}
    public String placeholder(String key,String fallback){Object value=lookup(placeholders,key);return value==null?fallback:value.toString();}
    public Path folder() { return folder; }
    private static Object lookup(Map<String,Object> root,String key) {
        Object current=root;
        for(String part:key.split("\\.")) { if(!(current instanceof Map<?,?> map))return null;current=map.get(part); }
        return current;
    }
}
