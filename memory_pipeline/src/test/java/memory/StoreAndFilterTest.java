package memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreAndFilterTest {

    @TempDir
    Path dir;

    private static MemoryRecord rec(String id, String ns) {
        return new MemoryRecord(id, ns, "feature-1", "2026-11-22T10:00:00Z", "decision",
                "A decision worth keeping for later work.", MemoryRecord.ACTIVE, null);
    }

    @Test
    void recordSurvivesReopeningTheFile() {
        Path f = dir.resolve("m.json");
        new JsonFileMemoryStore(f).put(rec("mem-001", "serviceforge:main:dev"));
        MemoryStore reopened = new JsonFileMemoryStore(f);
        assertEquals(1, reopened.size());
        assertEquals("feature-1", reopened.find("mem-001").orElseThrow().sourceTask());
    }

    @Test
    void duplicateIdIsRejected() {
        MemoryStore s = new JsonFileMemoryStore(dir.resolve("m.json"));
        s.put(rec("mem-001", "serviceforge:main:dev"));
        assertThrows(IllegalArgumentException.class, () -> s.put(rec("mem-001", "serviceforge:main:dev")));
    }

    @Test
    void supersededRecordIsNotReturnedAsActive() {
        MemoryStore s = new JsonFileMemoryStore(dir.resolve("m.json"));
        s.put(rec("mem-001", "ns"));
        s.markSuperseded("mem-001", "mem-002");
        assertTrue(s.search(Set.of("ns"), true).isEmpty());
        assertEquals(1, s.search(Set.of("ns"), false).size());
    }

    private final RetainDiscardFilter filter = new RetainDiscardFilter();

    @Test
    void keepsAnAtomicDecision() {
        assertTrue(filter.check("feature-2/adr", "decision",
                "Availability is derived from stock minus active reservations.").keep());
    }

    @Test
    void rejectsNoiseAndDumps() {
        List<String> noise = List.of(
                "",
                "   ",
                "too short",
                "Sure, thanks! Let me know if you need anything else.",
                "2026-11-22 10:15:01 ERROR Connection pool exhausted while booking",
                "INFO Started ServiceForgeApplication in 3.2 seconds",
                "java.lang.IllegalStateException at com.serviceforge.service.X.bookJob(X.java:42)",
                "line one of a dump\nline two of a dump",
                "user: how do I book a job?\nassistant: call bookJob",
                "x".repeat(400));
        for (String n : noise) {
            assertFalse(filter.check("task", "fact", n).keep(), "should reject: " + n);
        }
    }

    @Test
    void rejectsMissingProvenanceAndBadType() {
        String ok = "A decision worth keeping for later work.";
        assertFalse(filter.check("", "decision", ok).keep());
        assertFalse(filter.check(null, "decision", ok).keep());
        assertFalse(filter.check("task", "gossip", ok).keep());
    }
}
