package com.pixelclub.teams.money;

import java.lang.reflect.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.*;

/** Uses the pack's real result/reason API and independently verifies the pinned file provider's receipt. */
public final class PackEconomy implements DurableEconomy {
    private final Path accounts;
    private final Map<UUID,Long> anchors=new java.util.concurrent.ConcurrentHashMap<>();
    public PackEconomy(Path gameDirectory) { accounts=gameDirectory.resolve("config/impactor/economy/accounts/users").toAbsolutePath().normalize(); }
    private Object service() throws ReflectiveOperationException {
        Class<?> pack=Class.forName("com.pixelclub.pack.PixelclubPack");
        Object instance=pack.getMethod("getInstance").invoke(null);
        if(instance==null)throw new IllegalStateException("pack not ready");
        Object service=pack.getMethod("getEconomy").invoke(instance);
        if(service==null)throw new IllegalStateException("economy not ready");
        return service;
    }
    private Object call(String name,Class<?>[] types,Object... args) throws Exception {
        Object service=service();
        return Class.forName("com.pixelclub.pack.econ.EconomyService").getMethod(name,types).invoke(service,args);
    }
    @Override public boolean available() {
        try { return Boolean.TRUE.equals(call("isAvailable",new Class<?>[0])) && provider().toLowerCase(java.util.Locale.ROOT).contains("impactor"); }
        catch(Exception e) { return false; }
    }
    @Override public String provider() {
        try { return String.valueOf(call("providerName",new Class<?>[0])); }
        catch(Exception e) { return "unavailable"; }
    }
    @Override public boolean hasAccount(UUID player) throws Exception {
        return Boolean.TRUE.equals(call("hasAccount",new Class<?>[]{UUID.class,String.class},player,""));
    }
    @Override public double balance(UUID player) throws Exception {
        if(!hasAccount(player))throw new IllegalStateException("account absent");
        double value=((Number)call("balance",new Class<?>[]{UUID.class,String.class},player,"")).doubleValue();
        if(!Double.isFinite(value)||value<0)throw new IllegalStateException("invalid provider balance");
        return value;
    }
    @Override public boolean change(UUID player,double delta,String reason) throws Exception {
        if(!Double.isFinite(delta)||delta==0||!hasAccount(player))throw new IllegalArgumentException("invalid transfer");
        anchors.put(player,WalletFence.checkpoint());
        Object result=call(delta<0?"withdraw":"deposit",new Class<?>[]{UUID.class,String.class,double.class,String.class},player,"",Math.abs(delta),reason);
        return Boolean.TRUE.equals(Class.forName("com.pixelclub.pack.econ.EconomyService$Result").getMethod("success").invoke(result));
    }
    @Override public boolean flushConfirmed(UUID player,double expected) throws Exception {
        if(!hasAccount(player)||!same(balance(player),expected))return false;
        Class<?> api=Class.forName("net.impactdev.impactor.api.economy.EconomyService");
        Class<?> accountApi=Class.forName("net.impactdev.impactor.api.economy.accounts.Account");
        Object impactor=api.getMethod("instance").invoke(null);
        // UUID overload resolves the primary currency through the provider, without caching the Account.
        Object account=((CompletableFuture<?>)api.getMethod("account",UUID.class).invoke(impactor,player)).get(3,TimeUnit.SECONDS);
        ((CompletableFuture<?>)api.getMethod("save",accountApi).invoke(impactor,account)).get(3,TimeUnit.SECONDS);
        if(!WalletFence.awaitQuiet(player,false,anchors.getOrDefault(player,WalletFence.checkpoint()),3000))return false;
        Class<?> currencyApi=Class.forName("net.impactdev.impactor.api.economy.currency.Currency");
        Object currency=accountApi.getMethod("currency").invoke(account);
        Object key=currencyApi.getMethod("key").invoke(currency);
        String currencyKey=String.valueOf(Class.forName("net.kyori.adventure.key.Key").getMethod("asString").invoke(key));
        String id=player.toString();Path path=accounts.resolve(id.substring(0,2)).resolve(id+".conf").normalize();
        if(!path.startsWith(accounts)||!Files.isRegularFile(path)||Files.isSymbolicLink(path))return false;
        WalletFence.IoHandle handle=WalletFence.handle(player,false);
        if(handle==null||!handle.relativeTarget().toAbsolutePath().normalize().equals(path)||!handle.lock().tryLock(3,TimeUnit.SECONDS))return false;
        try {
        // ConfigurateProvider.save swallows IO failures. A completed future alone is insufficient.
        String content=Files.readString(path,StandardCharsets.UTF_8);
        String quoted=Pattern.quote(currencyKey);
        Pattern pattern=Pattern.compile("(?m)^\\s*(?:\""+quoted+"\"|"+quoted+")\\s*[=:]\\s*([-+0-9.eE]+)\\s*[,;]?\\s*$");
        Matcher match=pattern.matcher(content); if(!match.find())return false;
        double durable=Double.parseDouble(match.group(1));if(match.find()||!same(durable,expected))return false;
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.WRITE)){channel.force(true);}
        }finally{handle.lock().unlock();}
        // Cache loading and balance access can acquire account/provider locks: never await them under the IO lock.
        Object again=((CompletableFuture<?>)api.getMethod("account",UUID.class).invoke(impactor,player)).get(3,TimeUnit.SECONDS);
        return again==account&&WalletFence.quiet(player,false)&&same(balance(player),expected);
    }
    public static boolean same(double a,double b) { return Double.isFinite(a)&&Double.isFinite(b)&&Math.abs(a-b)<=Math.max(1e-7,Math.ulp(Math.max(Math.abs(a),Math.abs(b)))*4); }
}
