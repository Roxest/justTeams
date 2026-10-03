package com.pixelclub.teams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import fr.xephi.authme.api.v3.AuthMeApi;
import static org.junit.jupiter.api.Assertions.*;
class AuthMeBridgeTest {
    private final UUID uuid=UUID.randomUUID();
    record Online(UUID getUniqueId,String getName,boolean isOnline) implements Player {}
    @BeforeEach void prepare(){Bukkit.server=new Object();Bukkit.manager=name->()->true;Bukkit.player=new Online(uuid,"Fixture",true);AuthMeApi.loggedIn=false;AuthMeApi.calls=0;}
    @Test void activeUnauthenticatedSessionDenied(){assertFalse(AuthMeBridge.authenticated(uuid,"Fixture"));assertEquals(1,AuthMeApi.calls);}
    @Test void activeAuthenticatedMatchingIdentityAllowed(){AuthMeApi.loggedIn=true;assertTrue(AuthMeBridge.authenticated(uuid,"Fixture"));}
    @Test void differentUuidDeniedBeforeAuthQuery(){AuthMeApi.loggedIn=true;Bukkit.player=new Online(UUID.randomUUID(),"Fixture",true);assertFalse(AuthMeBridge.authenticated(uuid,"Fixture"));assertEquals(0,AuthMeApi.calls);}
    @Test void differentNameCaseDeniedBeforeAuthQuery(){AuthMeApi.loggedIn=true;Bukkit.player=new Online(uuid,"fixture",true);assertFalse(AuthMeBridge.authenticated(uuid,"Fixture"));assertEquals(0,AuthMeApi.calls);}
    @Test void staleOfflinePlayerDenied(){AuthMeApi.loggedIn=true;Bukkit.player=new Online(uuid,"Fixture",false);assertFalse(AuthMeBridge.authenticated(uuid,"Fixture"));assertEquals(0,AuthMeApi.calls);}
    @Test void absentProviderDefersToNativeOnlineMode(){Bukkit.manager=name->null;assertNull(AuthMeBridge.authenticated(uuid,"Fixture"));Bukkit.server=null;assertNull(AuthMeBridge.authenticated(uuid,"Fixture"));}
    @Test void disabledProviderDenied(){Bukkit.manager=name->()->false;assertFalse(AuthMeBridge.authenticated(uuid,"Fixture"));}
    @Test void providerExceptionFailsClosed(){Bukkit.manager=name->{throw new IllegalStateException("fixture");};assertFalse(AuthMeBridge.authenticated(uuid,"Fixture"));}
}
