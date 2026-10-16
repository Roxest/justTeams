package com.pixelclub.teams.money;

import com.google.gson.*;
import com.pixelclub.teams.store.*;
import com.pixelclub.teams.store.TeamStore.Operation;
import java.math.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Serializes bank operations and quarantines every ambiguous external outcome instead of guessing. */
public final class BankService {
    public record Result(boolean success,boolean pending,String code,String opid,double playerBefore,double playerAfter,double bankBefore,double bankAfter) {}
    private final TeamStore store;
    private final DurableEconomy economy;
    private final Path wal;
    private double cap;
    /** K-1 (+k1a1): the player's leg as a keyed move asked off the server thread; null = the synchronous path only. */
    private final AsyncLedger ledger;
    /** Where an answer is handed back (production: server::execute). */
    private final java.util.concurrent.Executor main;
    public BankService(TeamStore store,DurableEconomy economy,Path wal,double cap) {this(store,economy,wal,cap,null,Runnable::run);}
    public BankService(TeamStore store,DurableEconomy economy,Path wal,double cap,AsyncLedger ledger,java.util.concurrent.Executor main) {
        if(!Double.isFinite(cap))throw new IllegalArgumentException("bank cap");
        this.store=store;this.economy=economy;this.wal=wal;this.cap=cap;this.ledger=ledger;this.main=main;
    }

    /** {@code LedgerReceipts.adjustAsync} of the player's primary wallet (floor 0 for a withdraw from the wallet). */
    public interface AsyncLedger {
        boolean active();
        java.util.concurrent.CompletableFuture<String> adjust(UUID player,double delta,String key,long deadlineMs);
    }
    public static final AsyncLedger PXC_LEDGER=new AsyncLedger(){
        @Override public boolean active(){return LedgerReceipt.active();}
        @Override public java.util.concurrent.CompletableFuture<String> adjust(UUID player,double delta,String key,long deadlineMs){return LedgerReceipt.adjustAsync(player,delta,key,deadlineMs);}
    };
    public boolean available(){return economy.available();}
    /** Donor <=0 means uncapped; reload shares the same monitor as every money operation. */
    public synchronized void setCap(double cap){if(!Double.isFinite(cap))throw new IllegalArgumentException("bank cap");this.cap=cap;}
    /** Donor paid features consume team funds first; joining/creation pass bankFirst=false. */
    public synchronized Result charge(String opid,UUID player,double rawCost,boolean bankFirst) {
        double cost;
        try {cost=normalize(rawCost,true);}catch(RuntimeException e){return refuse("INVALID_COST",opid);}
        if(cost==0)return new Result(true,false,"FREE_CONFIGURED",opid,0,0,0,0);
        JsonObject payload=new JsonObject();
        try {
            if(store.operation(opid).isPresent())return refuse("COST_ALREADY_SUBMITTED",opid);
            Optional<Team> current=store.teamOf(player);int teamId=current.map(Team::id).orElse(0);
            double bankBefore=current.map(Team::balance).orElse(0d);
            if(store.isFrozen(teamId,player)||!economy.available()||!economy.hasAccount(player))return refuse("COST_UNAVAILABLE",opid);
            double before=economy.balance(player);
            // New charge amounts have the currency's two-decimal precision; opening subcent residues remain untouched.
            double fromBank=bankFirst?Math.min(cost,BigDecimal.valueOf(bankBefore).setScale(2,RoundingMode.FLOOR).doubleValue()):0;
            double fromPlayer=BigDecimal.valueOf(cost).subtract(BigDecimal.valueOf(fromBank)).doubleValue();
            if(before<fromPlayer)return refuse("INSUFFICIENT_PLAYER",opid);
            payload.addProperty("playerBefore",before);payload.addProperty("playerExpected",before-fromPlayer);
            payload.addProperty("bankBefore",bankBefore);payload.addProperty("bankExpected",bankBefore-fromBank);
            payload.addProperty("fromBank",fromBank);payload.addProperty("fromPlayer",fromPlayer);
            payload.addProperty("effectApplied",false);
            String ownerNonce=UUID.randomUUID().toString();payload.addProperty("ownerNonce",ownerNonce);
            Operation claimed=store.beginOperation(opid,teamId,player,"FEATURE_COST",cost,"PREPARED",payload.toString());
            if(!JsonParser.parseString(claimed.payload()).getAsJsonObject().get("ownerNonce").getAsString().equals(ownerNonce))return refuse("COST_ALREADY_CLAIMED",opid);
            append(opid,"PREPARED",payload);
            if(fromBank>0) {
                TeamStore.BankResult result=store.mutateBank(opid+"_bank",teamId,player,-fromBank,cap,false,true);
                if(!result.applied()) {store.updateOperation(opid,"CANCELLED",payload.toString());return refuse("BANK_COST_REFUSED",opid);}
            }
            if(fromPlayer>0) {
                store.updateOperation(opid,"PLAYER_INTENT",payload.toString());append(opid,"PLAYER_INTENT",payload);
                boolean charged=economy.change(player,-fromPlayer,"pxc:team-feature:"+opid);
                // +xs4: under pxc-ledger a refusal is definite (keyed move); nothing was taken from the bank, so cancel instead of freezing
                if(!charged&&fromBank==0&&economy.definiteRefusals()){store.updateOperation(opid,"CANCELLED",payload.toString());append(opid,"CANCELLED",payload);return refuse("PLAYER_COST_REFUSED",opid);}
                if(!charged||!economy.flushConfirmed(player,before-fromPlayer))return freeze(opid,"COST_PLAYER_UNKNOWN",payload);
            }
            store.updateOperation(opid,"CHARGED",payload.toString());append(opid,"CHARGED",payload);
            return new Result(true,false,"CHARGED",opid,before,before-fromPlayer,bankBefore,bankBefore-fromBank);
        }catch(Exception e) {
            try {if(store.operation(opid).isPresent())return freeze(opid,"COST_"+e.getClass().getSimpleName(),payload);}catch(Exception ignored){}
            return refuse("COST_STORAGE_UNAVAILABLE",opid);
        }
    }
    /** False is a definite domain refusal; thrown/ambiguous SQL outcomes must call unknownCharge instead. */
    public synchronized boolean finishCharge(String opid,boolean actionSucceeded) throws Exception {
        try {
        Optional<Operation> found=store.operation(opid);if(found.isEmpty())return true;
        Operation operation=found.get();if(!operation.kind().equals("FEATURE_COST"))return false;
        if(operation.phase().equals("DONE"))return actionSucceeded;
        if(operation.phase().equals("COMPENSATED"))return !actionSucceeded;
        if(!operation.phase().equals("CHARGED"))return false;
        JsonObject payload=JsonParser.parseString(operation.payload()).getAsJsonObject();
        double fromBank=value(payload,"fromBank"),fromPlayer=value(payload,"fromPlayer");
        if(!actionSucceeded) {
            store.updateOperation(opid,"REFUND_INTENT",payload.toString());append(opid,"REFUND_INTENT",payload);
            if(fromPlayer>0&&(!economy.change(operation.player(),fromPlayer,"pxc:team-feature-refund:"+opid)||!economy.flushConfirmed(operation.player(),value(payload,"playerBefore")))){
                freeze(opid,"FEATURE_REFUND_UNKNOWN",payload);return false;
            }
            // A rejected feature may restore its verified opening balance even if that balance predates a lower cap.
            double bankBefore=value(payload,"bankBefore");
            if(!Double.isFinite(bankBefore)||bankBefore<0)throw new IllegalArgumentException("Invalid charge baseline");
            double refundCap=cap>0?Math.max(cap,bankBefore):cap;
            if(fromBank>0&&!store.mutateBank(opid+"_refund",operation.teamId(),operation.player(),fromBank,refundCap,false,true).applied()){
                freeze(opid,"FEATURE_BANK_REFUND_UNKNOWN",payload);return false;
            }
        }
        for(String child:List.of(opid+"_bank",opid+"_refund"))if(store.operation(child).isPresent())store.updateOperation(child,"DONE","{}");
        store.updateOperation(opid,actionSucceeded?"DONE":"COMPENSATED",payload.toString());append(opid,actionSucceeded?"DONE":"COMPENSATED",payload);
        return true;
        }catch(Exception error) {
            try{if(store.operation(opid).isPresent())store.freezeOperation(opid,"FEATURE_SETTLEMENT_"+error.getClass().getSimpleName());}catch(Exception freezeError){error.addSuppressed(freezeError);}
            throw error;
        }
    }
    private boolean bankReceiptMatches(String opid,double delta)throws Exception{
        Operation row=store.operation(opid).orElse(null);if(row==null||!row.effectApplied())return false;
        JsonObject receipt=JsonParser.parseString(row.payload()).getAsJsonObject();
        if(!receipt.has("bankDelta")||!receipt.has("bankBalanceBefore")||!receipt.has("bankBalanceAfter"))return false;
        double recorded=receipt.get("bankDelta").getAsDouble(),before=receipt.get("bankBalanceBefore").getAsDouble(),after=receipt.get("bankBalanceAfter").getAsDouble();
        return PackEconomy.same(recorded,delta)&&PackEconomy.same(after-before,delta);
    }
    public synchronized void unknownCharge(String opid,String reason) throws Exception {if(store.operation(opid).isPresent())store.freezeOperation(opid,reason);}
    public synchronized Result transfer(String opid,UUID player,boolean deposit,double rawAmount,double rawFee,boolean bypass) {
        double amount,fee;
        try { amount=normalize(rawAmount,false);fee=normalize(rawFee,true); }
        catch(RuntimeException e){return refuse("INVALID_AMOUNT",opid);}
        if(opid==null||!opid.matches("[A-Za-z0-9_-]{8,80}")||player==null)return refuse("INVALID_OPERATION",opid);
        Team team;Member member;double before=0,bankBefore=0;JsonObject payload=new JsonObject();
        try {
            Optional<Operation> prior=store.operation(opid);
            if(prior.isPresent()) {
                Operation old=prior.get();
                if(!Objects.equals(old.player(),player)||!old.kind().equals(deposit?"DEPOSIT":"WITHDRAW")||!PackEconomy.same(old.amount(),amount))return refuse("OPID_COLLISION",opid);
                return fromPrior(old);
            }
            team=store.teamOf(player).orElseThrow(()->new IllegalArgumentException("NO_TEAM"));
            member=store.member(player).orElseThrow(()->new IllegalArgumentException("NO_MEMBER"));
            bankBefore=team.balance();
            if(store.isFrozen(team.id(),player))return refuse("FROZEN_PENDING_RECOVERY",opid);
            if(!deposit&&!member.flag("can_withdraw")&&!bypass)return refuse("DENIED",opid);
            if(!Double.isFinite(bankBefore+(deposit?amount:-amount)))return refuse("INVALID_RESULT",opid);
            if(deposit&&cap>0&&bankBefore+amount>cap)return refuse("BANK_CAP",opid);
            if(!deposit&&bankBefore<amount)return refuse("INSUFFICIENT_BANK",opid);
            if(!economy.available()||!economy.hasAccount(player))return refuse("PROVIDER_UNAVAILABLE",opid);
            before=economy.balance(player);
            if(deposit&&before<amount+fee)return refuse("INSUFFICIENT_PLAYER",opid);
            if(!deposit&&amount<=fee)return refuse("FEE_EXCEEDS_AMOUNT",opid);
            double playerExpected=deposit?before-amount-fee:before+amount-fee;
            if(!Double.isFinite(playerExpected)||playerExpected<0)return refuse("INVALID_RESULT",opid);
            payload.addProperty("playerBefore",before);payload.addProperty("playerExpected",playerExpected);
            payload.addProperty("bankBefore",bankBefore);payload.addProperty("bankExpected",bankBefore+(deposit?amount:-amount));
            payload.addProperty("fee",fee);payload.addProperty("effectApplied",false);
            String ownerNonce=UUID.randomUUID().toString();payload.addProperty("ownerNonce",ownerNonce);
            Operation claimed=store.beginOperation(opid,team.id(),player,deposit?"DEPOSIT":"WITHDRAW",amount,"PREPARED",payload.toString());
            JsonObject claimPayload=JsonParser.parseString(claimed.payload()).getAsJsonObject();
            if(!claimPayload.has("ownerNonce")||!claimPayload.get("ownerNonce").getAsString().equals(ownerNonce))return fromPrior(claimed);
            append(opid,"PREPARED",payload);
            if(deposit) {
                store.updateOperation(opid,"PLAYER_INTENT",payload.toString());append(opid,"PLAYER_INTENT",payload);
                boolean changed=economy.change(player,-amount-fee,"pxc:team-bank:"+opid);
                // +xs4: under pxc-ledger a refusal is definite (keyed move) and the bank is not touched yet: cancel, not freeze
                if(!changed&&economy.definiteRefusals()){store.updateOperation(opid,"CANCELLED",payload.toString());append(opid,"CANCELLED",payload);return refuse("PLAYER_DEBIT_REFUSED",opid);}
                if(!changed||!economy.flushConfirmed(player,playerExpected))return freeze(opid,"PLAYER_OUTCOME_UNKNOWN",payload);
                store.updateOperation(opid,"PLAYER_APPLIED",payload.toString());append(opid,"PLAYER_APPLIED",payload);
                TeamStore.BankResult bank=store.mutateBank(opid,team.id(),player,amount,cap,false,bypass);
                if(!bank.applied()) {
                    // Never compensate an uncertain SQL commit. A definite conditional refusal is recoverable.
                    if(bank.duplicate())return freeze(opid,"UNEXPECTED_DUPLICATE",payload);
                    store.updateOperation(opid,"REFUND_INTENT",payload.toString());append(opid,"REFUND_INTENT",payload);
                    if(!economy.change(player,amount+fee,"pxc:team-bank-refund:"+opid)||!economy.flushConfirmed(player,before))return freeze(opid,"REFUND_OUTCOME_UNKNOWN",payload);
                    store.updateOperation(opid,"COMPENSATED",payload.toString());append(opid,"COMPENSATED",payload);
                    return new Result(false,false,"COMPENSATED",opid,before,before,bankBefore,bank.balance());
                }
            } else {
                TeamStore.BankResult bank=store.mutateBank(opid,team.id(),player,-amount,cap,true,bypass);
                if(!bank.applied()) {
                    store.updateOperation(opid,"CANCELLED",payload.toString());append(opid,"CANCELLED",payload);
                    return new Result(false,false,bank.reason(),opid,before,before,bankBefore,bank.balance());
                }
                store.updateOperation(opid,"PLAYER_INTENT",payload.toString());append(opid,"PLAYER_INTENT",payload);
                if(!economy.change(player,amount-fee,"pxc:team-bank:"+opid)||!economy.flushConfirmed(player,playerExpected))return freeze(opid,"PLAYER_OUTCOME_UNKNOWN",payload);
            }
            // Re-read both legs. A result is success only after account persistence and SQL commit receipts.
            // +xs1: the bank leg is proven by this operation's own committed row (delta recorded under the team-row lock),
            // not by the live balance: another backend may legally move the same team's balance in between.
            double after=economy.balance(player);double bankAfter=store.team(team.id()).orElseThrow().balance();
            if(!PackEconomy.same(after,playerExpected)||!bankReceiptMatches(opid,deposit?amount:-amount))return freeze(opid,"FINAL_READBACK_MISMATCH",payload);
            store.updateOperation(opid,"DONE",payload.toString());append(opid,"DONE",payload);
            return new Result(true,false,"DONE",opid,before,after,bankBefore,bankAfter);
        } catch(Exception e) {
            try {
                if(store.operation(opid).isPresent())return freeze(opid,"EXCEPTION_"+e.getClass().getSimpleName(),payload);
            }catch(Exception ignored){}
            if(e instanceof IllegalArgumentException)return refuse("INVALID_OR_NO_TEAM",opid);
            return new Result(false,true,"STORAGE_OUTCOME_UNKNOWN",opid,before,before,bankBefore,bankBefore);
        }
    }
    // ------------------------------------------------------------------ K-1 (+k1a1): bank ops under pxc-ledger, async

    /** One keyed ledger move of an op: PLAYER = the player's leg, REFUND = a deposit's refund after the bank refused. */
    record Step(String opid,UUID player,String kind,String key,double delta) {}
    static String playerKey(String opid){return "pxc:team-bank:"+opid;}
    static String refundKey(String opid){return "pxc:team-bank-refund:"+opid;}
    /** Ops whose step is being asked now (never asked twice at once). */
    private final Set<String> asking=java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** FROZEN ops waiting for a retry of their step with the same key: opid -> {attempts, dueAtMillis}. */
    private final Map<String,long[]> retry=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile java.util.function.BiConsumer<UUID,Result> retried=(p,r)->{};
    static final long RETRY_MIN_MS=1_000,RETRY_MAX_MS=60_000;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("justteams");

    /** Told (on the server thread) when a retried op ends; the player may be offline or on another backend. */
    public void onRetried(java.util.function.BiConsumer<UUID,Result> listener){retried=listener==null?(p,r)->{}:listener;}
    public boolean ledgerAsync(){return ledger!=null&&ledger.active();}
    /** Ops waiting for a retry (tests, status). */
    public Set<String> retrying(){return Set.copyOf(retry.keySet());}

    /**
     * {@link #transfer} with the result handed to {@code done} on the server thread. pxc-ledger not active: exactly
     * {@code transfer}, and {@code done} runs before this returns. pxc-ledger active: the bank side runs here as before
     * (operation row, WAL, the bank leg of a withdraw), the player's leg is ONE keyed move asked off the server thread
     * ({@link LedgerAsks}), and the op is finished on the server thread when the answer is definite: DONE, or
     * CANCELLED/COMPENSATED for a definite refusal (a first-attempt FAILED = not applied). Only an answer still open at
     * the deadline freezes the op (LEDGER_PENDING); it is then asked again with the same key until definite.
     */
    public void transferAsync(String opid,UUID player,boolean deposit,double rawAmount,double rawFee,boolean bypass,java.util.function.Consumer<Result> done) {
        if(!ledgerAsync()){done.accept(transfer(opid,player,deposit,rawAmount,rawFee,bypass));return;}
        Object begun;
        synchronized(this){begun=beginAsync(opid,player,deposit,rawAmount,rawFee,bypass);}
        if(begun instanceof Result r){done.accept(r);return;}
        send((Step)begun,true,done);
    }

    /** The bank side up to the player's leg; a Result = finished here, a Step = ask the ledger. */
    private Object beginAsync(String opid,UUID player,boolean deposit,double rawAmount,double rawFee,boolean bypass) {
        double amount,fee;
        try { amount=normalize(rawAmount,false);fee=normalize(rawFee,true); }
        catch(RuntimeException e){return refuse("INVALID_AMOUNT",opid);}
        if(opid==null||!opid.matches("[A-Za-z0-9_-]{8,80}")||player==null)return refuse("INVALID_OPERATION",opid);
        double bankBefore=0;JsonObject payload=new JsonObject();
        try {
            Optional<Operation> prior=store.operation(opid);
            if(prior.isPresent()) {
                Operation old=prior.get();
                if(!Objects.equals(old.player(),player)||!old.kind().equals(deposit?"DEPOSIT":"WITHDRAW")||!PackEconomy.same(old.amount(),amount))return refuse("OPID_COLLISION",opid);
                return fromPrior(old);
            }
            Team team=store.teamOf(player).orElseThrow(()->new IllegalArgumentException("NO_TEAM"));
            Member member=store.member(player).orElseThrow(()->new IllegalArgumentException("NO_MEMBER"));
            bankBefore=team.balance();
            if(store.isFrozen(team.id(),player))return refuse("FROZEN_PENDING_RECOVERY",opid);
            if(!deposit&&!member.flag("can_withdraw")&&!bypass)return refuse("DENIED",opid);
            if(!Double.isFinite(bankBefore+(deposit?amount:-amount)))return refuse("INVALID_RESULT",opid);
            if(deposit&&cap>0&&bankBefore+amount>cap)return refuse("BANK_CAP",opid);
            if(!deposit&&bankBefore<amount)return refuse("INSUFFICIENT_BANK",opid);
            // no hasAccount()/balance() here: under the ledger those may wait on MySQL; the keyed move opens the wallet
            // on the ledger's thread and checks the player's funds in the same statement (floor 0 -> BELOW_FLOOR)
            if(!economy.available())return refuse("PROVIDER_UNAVAILABLE",opid);
            if(!deposit&&amount<=fee)return refuse("FEE_EXCEEDS_AMOUNT",opid);
            double delta=deposit?-(amount+fee):amount-fee;
            payload.addProperty("bankBefore",bankBefore);payload.addProperty("bankExpected",bankBefore+(deposit?amount:-amount));
            payload.addProperty("fee",fee);payload.addProperty("effectApplied",false);payload.addProperty("async","k1a1");
            String ownerNonce=UUID.randomUUID().toString();payload.addProperty("ownerNonce",ownerNonce);
            Operation claimed=store.beginOperation(opid,team.id(),player,deposit?"DEPOSIT":"WITHDRAW",amount,"PREPARED",payload.toString());
            JsonObject claimPayload=JsonParser.parseString(claimed.payload()).getAsJsonObject();
            if(!claimPayload.has("ownerNonce")||!claimPayload.get("ownerNonce").getAsString().equals(ownerNonce))return fromPrior(claimed);
            append(opid,"PREPARED",payload);
            if(!deposit) {
                TeamStore.BankResult bank=store.mutateBank(opid,team.id(),player,-amount,cap,true,bypass);
                if(!bank.applied()) {
                    store.updateOperation(opid,"CANCELLED",payload.toString());append(opid,"CANCELLED",payload);
                    return new Result(false,false,bank.reason(),opid,0,0,bankBefore,bank.balance());
                }
            }
            // the step is written with PLAYER_INTENT, i.e. only after a withdraw's bank leg: a retry never pays a
            // player for a bank debit that did not happen
            step(payload,"PLAYER",playerKey(opid),delta);
            store.updateOperation(opid,"PLAYER_INTENT",payload.toString());append(opid,"PLAYER_INTENT",payload);
            asking.add(opid);
            return new Step(opid,player,"PLAYER",playerKey(opid),delta);
        } catch(Exception e) {
            try {
                if(store.operation(opid).isPresent())return freeze(opid,"EXCEPTION_"+e.getClass().getSimpleName(),payload);
            }catch(Exception ignored){}
            if(e instanceof IllegalArgumentException)return refuse("INVALID_OR_NO_TEAM",opid);
            return new Result(false,true,"STORAGE_OUTCOME_UNKNOWN",opid,0,0,bankBefore,bankBefore);
        }
    }
    private static void step(JsonObject payload,String kind,String key,double delta){
        payload.addProperty("ledgerStep",kind);payload.addProperty("ledgerKey",key);payload.addProperty("ledgerDelta",Double.toString(delta));
    }

    /** Asks the step's key off the server thread; the answer is acted on via {@code main}. */
    private void send(Step step,boolean fresh,java.util.function.Consumer<Result> done) {
        asking.add(step.opid());
        LedgerAsks.send(deadline->ledger.adjust(step.player(),step.delta(),step.key(),deadline),fresh).whenComplete((answer,error)->{
            String[] a=error!=null||answer==null?new String[]{"EXCEPTION "+(error==null?"null":error.getClass().getSimpleName()),"2"}:answer;
            try{main.execute(()->answered(step,a,done));}
            catch(RuntimeException stopped){asking.remove(step.opid());} // stopping: the op stays PLAYER_INTENT, frozen and adopted at the next boot
        });
    }
    private void answered(Step step,String[] answer,java.util.function.Consumer<Result> done) {
        Object next;
        synchronized(this){
            try{next=resume(step,answer);}
            catch(Exception e){
                next=new Result(false,true,"LEDGER_RESUME_"+e.getClass().getSimpleName(),step.opid(),0,0,0,0);
                // re-running the step is safe (same key; bank legs keyed by opid): retried like a pending answer
                try{if(store.operation(step.opid()).isPresent()){store.freezeOperation(step.opid(),"LEDGER_RESUME_"+e.getClass().getSimpleName());schedule(step.opid());}}catch(Exception ignored){}
            }finally{asking.remove(step.opid());}
        }
        if(next instanceof Step s){send(s,true,done);return;}
        done.accept((Result)next);
    }

    /** The step's answer is in (server thread, this monitor): finish the op, freeze it for a retry, or the next step. */
    Object resume(Step step,String[] answer)throws Exception {
        String opid=step.opid();
        Operation op=store.operation(opid).orElseThrow(()->new IllegalStateException("operation vanished"));
        JsonObject payload=JsonParser.parseString(op.payload()).getAsJsonObject();
        if(Set.of("DONE","COMPENSATED","CANCELLED").contains(op.phase()))return fromPrior(op);
        if(!step.kind().equals(payload.has("ledgerStep")?payload.get("ledgerStep").getAsString():""))return fromPrior(op); // decided meanwhile
        LedgerAsks.Outcome o=LedgerAsks.decide(answer);
        // a refund must land: a refusal that did not look at the key leaves it pending
        if(step.kind().equals("REFUND")&&o==LedgerAsks.Outcome.REFUSED&&!"KEY_CONFLICT".equals(answer[0]))o=LedgerAsks.Outcome.PENDING;
        boolean deposit=op.kind().equals("DEPOSIT");double amount=op.amount(),fee=value(payload,"fee"),bankBefore=value(payload,"bankBefore");
        if("KEY_CONFLICT".equals(answer[0])){retry.remove(opid);return staffFreeze(opid,"LEDGER_KEY_CONFLICT",payload);} // the key is another move's: staff
        if(o==LedgerAsks.Outcome.PENDING){
            Result r=op.phase().equals("FROZEN")?new Result(false,true,"LEDGER_PENDING",opid,0,0,bankBefore,value(payload,"bankExpected")):freeze(opid,"LEDGER_PENDING",payload);
            schedule(opid);
            LOG.warn("[pxc-teams] bank op {} {} still open after the ledger deadline ({}, ask {}); frozen, asked again with key {}",opid,step.kind(),answer[0],answer[1],step.key());
            return new Result(false,true,"LEDGER_PENDING",opid,0,0,r.bankBefore(),r.bankAfter());
        }
        retry.remove(opid);
        if(step.kind().equals("REFUND")) {
            for(String child:List.of(opid+"_bank",opid+"_refund"))if(store.operation(child).isPresent())store.updateOperation(child,"DONE","{}");
            payload.addProperty("ledgerStep","NONE");
            store.updateOperation(opid,"COMPENSATED",payload.toString());append(opid,"COMPENSATED",payload);
            return new Result(false,false,"COMPENSATED",opid,0,0,bankBefore,store.team(op.teamId()).map(Team::balance).orElse(bankBefore));
        }
        if(deposit) {
            if(o==LedgerAsks.Outcome.REFUSED) {
                payload.addProperty("ledgerStep","NONE");
                store.updateOperation(opid,"CANCELLED",payload.toString());append(opid,"CANCELLED",payload);
                return refuse("BELOW_FLOOR".equals(answer[0])?"INSUFFICIENT_PLAYER":"PLAYER_DEBIT_REFUSED",opid);
            }
            store.updateOperation(opid,"PLAYER_APPLIED",payload.toString());append(opid,"PLAYER_APPLIED",payload);
            TeamStore.BankResult bank=store.mutateBank(opid,op.teamId(),op.player(),amount,cap,false,false);
            if(!bank.applied()) {
                if(bank.duplicate())return staffFreeze(opid,"UNEXPECTED_DUPLICATE",payload);
                // a definite conditional refusal (cap, team gone, frozen): give the player's money back under its own key
                step(payload,"REFUND",refundKey(opid),amount+fee);
                store.updateOperation(opid,"REFUND_INTENT",payload.toString());append(opid,"REFUND_INTENT",payload);
                return new Step(opid,op.player(),"REFUND",refundKey(opid),amount+fee);
            }
        } else if(o==LedgerAsks.Outcome.REFUSED) {
            // the player's credit definitely did not apply: put the bank's money back (the refund may exceed a lowered cap)
            payload.addProperty("ledgerStep","NONE");
            store.updateOperation(opid,"BANK_REFUND_INTENT",payload.toString());append(opid,"BANK_REFUND_INTENT",payload);
            double refundCap=cap>0?Math.max(cap,bankBefore):cap;
            if(!store.mutateBank(opid+"_refund",op.teamId(),op.player(),amount,refundCap,false,true).applied())return freeze(opid,"BANK_REFUND_UNKNOWN",payload);
            store.updateOperation(opid+"_refund","DONE","{}");
            store.updateOperation(opid,"COMPENSATED",payload.toString());append(opid,"COMPENSATED",payload);
            return new Result(false,false,"PLAYER_CREDIT_REFUSED",opid,0,0,bankBefore,store.team(op.teamId()).map(Team::balance).orElse(bankBefore));
        }
        // the player's leg is in (SUCCESS / REPLAYED = committed in MySQL, once); the bank leg by its own committed row
        if(!bankReceiptMatches(opid,deposit?amount:-amount))return staffFreeze(opid,"FINAL_READBACK_MISMATCH",payload);
        payload.addProperty("ledgerStep","NONE");
        store.updateOperation(opid,"DONE",payload.toString());append(opid,"DONE",payload);
        double bankAfter=store.team(op.teamId()).map(Team::balance).orElse(value(payload,"bankExpected"));
        return new Result(true,false,"DONE",opid,0,0,bankBefore,bankAfter);
    }

    /** A freeze no retry may touch (the step is marked STAFF in the stored payload, so boot adoption skips it too). */
    private Result staffFreeze(String opid,String reason,JsonObject payload)throws Exception {
        payload.addProperty("ledgerStep","STAFF");store.updateOperation(opid,"FROZEN",payload.toString());
        return freeze(opid,reason,payload);
    }
    private void schedule(String opid){long[] b=retry.computeIfAbsent(opid,k->new long[2]);b[0]++;b[1]=System.currentTimeMillis()+Math.min(RETRY_MAX_MS,RETRY_MIN_MS<<Math.min(16,b[0]-1));}

    /**
     * Boot (after TeamsCore froze this backend's unfinished ops): FROZEN async ops whose step was written are asked
     * again with the same key. Ops without a step (xs6 and older, a PREPARED withdraw before its bank leg, a bank-side
     * compensation) are left to staff as before.
     */
    public void adopt(List<Operation> pending) {
        for(Operation op:pending) {
            if(!op.phase().equals("FROZEN")||!Set.of("DEPOSIT","WITHDRAW").contains(op.kind()))continue;
            try {
                JsonObject p=JsonParser.parseString(op.payload()).getAsJsonObject();
                String kind=p.has("ledgerStep")?p.get("ledgerStep").getAsString():"";
                if(!Set.of("PLAYER","REFUND").contains(kind)||!p.has("ledgerKey"))continue;
                if("LEDGER_KEY_CONFLICT".equals(p.has("freezeReason")?p.get("freezeReason").getAsString():""))continue;
                retry.put(op.opid(),new long[]{0,0});
            }catch(RuntimeException ignored){}
        }
    }

    /** Server tick (every second): asks due retries again, same key, one ask each, never waiting. */
    public void tickRetries(){tickRetries(System.currentTimeMillis());}
    void tickRetries(long now) {
        if(retry.isEmpty()||!ledgerAsync())return;
        for(var e:retry.entrySet()) {
            String opid=e.getKey();
            if(e.getValue()[1]>now||asking.contains(opid))continue;
            Step step;
            synchronized(this){
                try {
                    Operation op=store.operation(opid).orElse(null);
                    if(op==null||!op.phase().equals("FROZEN")){retry.remove(opid);continue;}
                    JsonObject p=JsonParser.parseString(op.payload()).getAsJsonObject();
                    step=new Step(opid,op.player(),p.get("ledgerStep").getAsString(),p.get("ledgerKey").getAsString(),Double.parseDouble(p.get("ledgerDelta").getAsString()));
                    if(!Set.of("PLAYER","REFUND").contains(step.kind())){retry.remove(opid);continue;}
                    schedule(opid); // next due time if this ask is still open
                }catch(Exception ex){schedule(opid);continue;}
            }
            UUID who=step.player();
            send(step,false,result->{if(!result.pending())LOG.warn("[pxc-teams] bank op {} settled after retry: {}",opid,result.code());retried.accept(who,result);});
        }
    }

    private Result freeze(String opid,String reason,JsonObject payload)throws Exception {
        store.freezeOperation(opid,reason);append(opid,"FROZEN_"+reason,payload);
        return new Result(false,true,reason,opid,value(payload,"playerBefore"),value(payload,"playerExpected"),value(payload,"bankBefore"),value(payload,"bankExpected"));
    }
    private static Result fromPrior(Operation old) {
        JsonObject payload=JsonParser.parseString(old.payload()).getAsJsonObject();
        boolean done=old.phase().equals("DONE");boolean settled=done||old.phase().equals("COMPENSATED")||old.phase().equals("CANCELLED");
        return new Result(done,!settled,"ALREADY_"+old.phase(),old.opid(),value(payload,"playerBefore"),done?value(payload,"playerExpected"):value(payload,"playerBefore"),value(payload,"bankBefore"),done?value(payload,"bankExpected"):value(payload,"bankBefore"));
    }
    private static double value(JsonObject payload,String key){return payload.has(key)?payload.get(key).getAsDouble():0;}
    private static Result refuse(String code,String opid){return new Result(false,false,code,opid,0,0,0,0);}
    public static double normalize(double raw,boolean zeroAllowed) {
        if(!Double.isFinite(raw)||raw<0||(!zeroAllowed&&raw==0))throw new IllegalArgumentException("amount");
        double value=BigDecimal.valueOf(raw).setScale(2,RoundingMode.HALF_UP).doubleValue();
        if(!Double.isFinite(value)||(!zeroAllowed&&value<=0))throw new IllegalArgumentException("amount rounds to zero");return value;
    }
    private void append(String opid,String phase,JsonObject payload)throws Exception {
        Files.createDirectories(wal.toAbsolutePath().getParent());JsonObject entry=new JsonObject();
        entry.addProperty("opid",opid);entry.addProperty("phase",phase);entry.addProperty("at",System.currentTimeMillis());entry.add("payload",payload.deepCopy());
        byte[] bytes=(entry+System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try(FileChannel channel=FileChannel.open(wal,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
    }
}
