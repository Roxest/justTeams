package com.pixelclub.teams.money;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Tracks discarded implicit save futures too; pending writers are fenced before a caller verifies disk state. */
public final class WalletFence {
    private record Key(UUID owner,boolean virtual){}
    public record IoHandle(Path relativeTarget,ReentrantLock lock){}
    public record Ticket(Key key,CompletableFuture<Void> reservation,long sequence){}
    private static final Map<Key,Map<CompletableFuture<?>,Long>> pending=new HashMap<>();
    private static final Map<Key,Long> failures=new HashMap<>();
    private static final Map<Key,IoHandle> handles=new HashMap<>();
    private static final AtomicLong sequence=new AtomicLong();
    private WalletFence(){}
    public static long checkpoint(){return sequence.get();}
    private static Key key(Object account)throws ReflectiveOperationException {
        Class<?> api=Class.forName("net.impactdev.impactor.api.economy.accounts.Account");
        return new Key((UUID)api.getMethod("owner").invoke(account),Boolean.TRUE.equals(api.getMethod("virtual").invoke(account)));
    }
    public static Ticket reserve(Object account) {
        try {
            Key key=key(account);synchronized(WalletFence.class){long seq=sequence.incrementAndGet();CompletableFuture<Void> reservation=new CompletableFuture<>();pending.computeIfAbsent(key,ignored->new IdentityHashMap<>()).put(reservation,seq);return new Ticket(key,reservation,seq);}
        }catch(ReflectiveOperationException error){throw new IllegalStateException("wallet fence cannot identify account",error);}
    }
    public static void bind(Ticket ticket,CompletableFuture<?> future){if(future==null)throw new IllegalStateException("wallet save returned no future");future.whenComplete((value,error)->finish(ticket,error));}
    public static void fail(Ticket ticket,Throwable error){finish(ticket,error);}
    private static synchronized void finish(Ticket ticket,Throwable error){Map<CompletableFuture<?>,Long> saves=pending.get(ticket.key());if(saves==null||saves.remove(ticket.reservation())==null)return;if(saves.isEmpty())pending.remove(ticket.key());if(error!=null)failures.merge(ticket.key(),ticket.sequence(),Math::max);WalletFence.class.notifyAll();}
    public static void track(Object account,CompletableFuture<?> future){Ticket ticket=reserve(account);try{bind(ticket,future);}catch(RuntimeException error){fail(ticket,error);throw error;}}
    public static void capture(Object provider,Path relativeTarget,Object account) {
        try {
            Field field=provider.getClass().getDeclaredField("ioLocks");field.setAccessible(true);Object cache=field.get(provider);
            Class<?> api=Class.forName("net.impactdev.impactor.relocations.com.github.benmanes.caffeine.cache.LoadingCache");
            Object lock=api.getMethod("get",Object.class).invoke(cache,relativeTarget);
            if(!(lock instanceof ReentrantLock reentrant))throw new IllegalStateException("wallet lock type changed");
            synchronized(WalletFence.class){handles.put(key(account),new IoHandle(relativeTarget,reentrant));}
        }catch(ReflectiveOperationException error){throw new IllegalStateException("wallet IO fence unavailable",error);}
    }
    public static synchronized boolean awaitQuiet(UUID owner,boolean virtual,long checkpoint,long timeoutMillis)throws InterruptedException {
        Key key=new Key(owner,virtual);long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(pending.containsKey(key)) {
            long left=deadline-System.nanoTime();if(left<=0)return false;
            TimeUnit.NANOSECONDS.timedWait(WalletFence.class,left);
        }
        return failures.getOrDefault(key,0L)<=checkpoint;
    }
    public static synchronized IoHandle handle(UUID owner,boolean virtual){return handles.get(new Key(owner,virtual));}
    public static synchronized boolean quiet(UUID owner,boolean virtual){return !pending.containsKey(new Key(owner,virtual));}
}
