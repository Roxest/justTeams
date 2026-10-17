package com.pixelclub.teams;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class MessagesKeyTest {
    /** PRD messages.yml has both lock lines; the refusal must pick the one that does not mention another server. */
    private static final Set<String> PRD_KEYS=Set.of("enderchest_locked","enderchest_locked_by_proxy");

    @Test void chestInUseHasATranslation(){
        assertEquals("enderchest_locked",Messages.key("CHEST_IN_USE",Map.of(),PRD_KEYS::contains));
    }

    @Test void unknownCodeStillFallsBackToNull(){
        assertNull(Messages.key("NO_SUCH_CODE",Map.of(),PRD_KEYS::contains));
    }

    /** X4 neutral text (Kraus 10-17): the X4 fallbacks never name a server, even though <server> is still passed. */
    @Test void x4FallbacksNameNoServer(){
        for(String c:new String[]{"REMOTE_TRAVEL","REMOTE_TRAVEL_REFUSED","REMOTE_WARP_LOCKED"}){
            String t=Messages.fallback(c);
            assertNotNull(t,c);
            assertFalse(t.contains("เซิร์ฟ")||t.contains("<server>")||t.contains("&l"),t);
        }
        assertTrue(Messages.fallback("REMOTE_TRAVEL_REFUSED").contains("<reason>"));
        assertEquals("กำลังต่อสู้อยู่ — จบการต่อสู้ก่อน",XferReason.thai("in a battle"));
        assertEquals("ทำไม่ได้ตอนนี้",XferReason.thai("pxc-xfer: boom"));
    }
}
