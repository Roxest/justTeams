package com.pixelclub.teams.chest;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The journal is a crash log for chest operations still in flight, not a history: PRD reached 305 MB in four days (2026-10-07). */
class ChestJournalTest {
    @TempDir Path temporary;

    private static JsonObject payload(int chars) {
        JsonObject payload = new JsonObject();
        payload.addProperty("generationBefore", 1);
        payload.addProperty("chestBefore", "x".repeat(chars));
        return payload;
    }

    @Test void settledOperationsDoNotAccumulate() throws Exception {
        Path file = temporary.resolve("chest-intents.jsonl");
        ChestJournal journal = new ChestJournal(file);
        UUID actor = UUID.randomUUID();
        // 150 clicks of ~60 KB each, every one settled in MySQL (DONE): 9 MB, past the 8 MiB the PRD build tolerates.
        for (int i = 0; i < 150; i++) {
            String op = UUID.randomUUID().toString();
            journal.append(op, 7, actor, "PREPARED", payload(20_000));
            journal.append(op, 7, actor, "CHEST_INTENT", payload(20_000));
            journal.append(op, 7, actor, "DONE", payload(20_000));
        }
        assertTrue(Files.size(file) < 8L << 20, "journal holds " + Files.size(file) + " bytes of settled operations");
        assertTrue(journal.load().values().stream().allMatch(head -> head.phase().getAsString().equals("DONE")));
    }
}
