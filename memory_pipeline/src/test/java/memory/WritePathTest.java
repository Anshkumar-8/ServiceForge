package memory;

import memory.WritePath.Proposal;
import memory.WritePath.Scope;
import memory.WritePath.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritePathTest {

    @TempDir
    Path dir;

    private static final String DECISION_TEXT =
            "Overlap detection compares buffered intervals with a 45-minute travel buffer.";

    @Test
    void storedRecordHasAllProvenanceFields() {
        Clock fixed = Clock.fixed(Instant.parse("2026-11-22T09:30:00Z"), ZoneOffset.UTC);
        MemoryStore store = new JsonFileMemoryStore(dir.resolve("m.json"));
        try (WritePath w = new WritePath(store, new RetainDiscardFilter(), Namespaces.forBranch("b1"), fixed)) {
            WriteResult r = w.submit(new Proposal("bugfix-1/overlap", "decision", DECISION_TEXT, Scope.MAIN)).join();
            assertTrue(r.accepted());
            MemoryRecord rec = store.find(r.id()).orElseThrow();
            assertEquals("bugfix-1/overlap", rec.sourceTask());
            assertEquals("2026-11-22T09:30:00Z", rec.timestamp());
            assertEquals("decision", rec.typeTag());
            assertEquals(MemoryRecord.ACTIVE, rec.status());
            assertEquals("serviceforge:main:dev", rec.namespace());
            assertNotNull(rec.content());
        }
    }

    @Test
    void rawChatterIsNotPersisted() {
        MemoryStore store = new JsonFileMemoryStore(dir.resolve("m.json"));
        try (WritePath w = new WritePath(store, Namespaces.forBranch("b1"))) {
            WriteResult r = w.submit(new Proposal("chat", "fact", "Sure, thanks! Anything else I can help with?",
                    Scope.MAIN)).join();
            assertFalse(r.accepted());
            assertEquals(0, store.size());
        }
    }

    @Test
    void supersedingMarksTheOldRecordAndHidesIt() {
        MemoryStore store = new JsonFileMemoryStore(dir.resolve("m.json"));
        try (WritePath w = new WritePath(store, Namespaces.forBranch("b1"))) {
            String oldId = w.submit(new Proposal("t", "decision", "Expiry is out of scope for reservations.",
                    Scope.BRANCH)).join().id();
            WriteResult newer = w.submit(new Proposal("t", "decision",
                    "Expiry stays out of scope until a Confirm step exists.", Scope.BRANCH, oldId)).join();
            assertTrue(newer.accepted());
            assertEquals(MemoryRecord.SUPERSEDED, store.find(oldId).orElseThrow().status());
            assertEquals(newer.id(), store.find(oldId).orElseThrow().supersededBy());
            List<MemoryRecord> active = store.search(Namespaces.forBranch("b1").readNamespaces(), true);
            assertEquals(1, active.size());
            assertEquals(newer.id(), active.get(0).id());
        }
    }

    @Test
    void cannotSupersedeAnUnknownRecord() {
        MemoryStore store = new JsonFileMemoryStore(dir.resolve("m.json"));
        try (WritePath w = new WritePath(store, Namespaces.forBranch("b1"))) {
            WriteResult r = w.submit(new Proposal("t", "decision", DECISION_TEXT, Scope.MAIN, "mem-999")).join();
            assertFalse(r.accepted());
            assertEquals(0, store.size());
        }
    }

    @Test
    void submitDoesNotBlockTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore slow = new JsonFileMemoryStore(dir.resolve("m.json")) {
            @Override
            public synchronized void put(MemoryRecord record) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.put(record);
            }
        };
        try (WritePath w = new WritePath(slow, Namespaces.forBranch("b1"))) {
            long start = System.nanoTime();
            CompletableFuture<WriteResult> f = w.submit(new Proposal("t", "decision", DECISION_TEXT, Scope.MAIN));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertFalse(f.isDone(), "write must still be in progress while the store is blocked");
            assertTrue(elapsedMs < 500, "submit returned in " + elapsedMs + " ms");
            release.countDown();
            assertTrue(f.get(5, TimeUnit.SECONDS).accepted());
        }
    }
}
