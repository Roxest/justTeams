package com.pixelclub.teams;

import com.pixelclub.teams.store.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.pixelclub.teams.api.TeamsDamagePolicy.Decision.*;

class TeamSnapshotsTest {
    @Test void liveRosterAndAlliesRefreshWhileLookupsPerformNoSql()throws Exception{
        String url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";AtomicInteger connections=new AtomicInteger();TeamStore store=new TeamStore(()->{connections.incrementAndGet();return DriverManager.getConnection(url);},"donut_");store.createFixtureSchema();UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();Team first=store.create("First","AA",a,"OwnerA","fixture"),second=store.create("Second","BB",b,"OwnerB","fixture");store.insertRow("player_cache",Map.of("player_uuid",c,"player_name","MemberC"));store.updateTeam(first.id(),a,Map.of("is_public",true,"pvp_enabled",false));assertTrue(store.joinPublic(c,first.id(),10));TeamSnapshots snapshots=new TeamSnapshots(store);snapshots.refreshAll();int initial=connections.get();for(int i=0;i<10000;i++){assertEquals(DENY,snapshots.policy(a,c));assertEquals(ALLOW,snapshots.policy(a,b));assertEquals(2,snapshots.subject(a).members().size());}assertEquals(initial,connections.get());
        assertTrue(store.allyRequest(first.id(),second.id(),a,10));assertTrue(store.allyAccept(first.id(),second.id(),b,10));snapshots.refreshAllies();assertEquals(DENY,snapshots.policy(a,b));assertTrue(store.leave(c));snapshots.refreshTeam(first.id());assertNull(snapshots.subject(c).team());assertEquals(ALLOW,snapshots.policy(a,c));assertEquals(UNKNOWN,snapshots.policy(a,UUID.randomUUID()));assertEquals(ALLOW,snapshots.policy(a,a));
    }
    @Test void FailedViewStaysUnknownUntilCompleteRefresh()throws Exception{
        String url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";TeamStore store=new TeamStore(()->DriverManager.getConnection(url),"donut_");store.createFixtureSchema();UUID a=UUID.randomUUID(),b=UUID.randomUUID();Team team=store.create("First","AA",a,"OwnerA","fixture");store.create("Second","BB",b,"OwnerB","fixture");TeamSnapshots snapshots=new TeamSnapshots(store);snapshots.refreshAll();assertEquals(ALLOW,snapshots.policy(a,b));snapshots.failed();assertEquals(UNKNOWN,snapshots.policy(a,b));snapshots.refreshTeam(team.id());snapshots.remember(a,"OwnerA");assertFalse(snapshots.healthy());assertEquals(UNKNOWN,snapshots.policy(a,b));snapshots.refreshAll();assertTrue(snapshots.healthy());assertEquals(ALLOW,snapshots.policy(a,b));assertThrows(UnsupportedOperationException.class,()->snapshots.subject(a).members().clear());
    }
}
