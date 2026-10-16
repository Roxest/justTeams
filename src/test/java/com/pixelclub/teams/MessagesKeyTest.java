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
}
