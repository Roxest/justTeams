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
    public BankService(TeamStore store,DurableEconomy economy,Path wal,double cap) {
        if(!Double.isFinite(cap))throw new IllegalArgumentException("bank cap");
        this.store=store;this.economy=economy;this.wal=wal;this.cap=cap;
    }
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
