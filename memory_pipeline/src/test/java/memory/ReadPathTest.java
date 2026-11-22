package memory;

import memory.WritePath.Proposal;
import memory.WritePath.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadPathTest {

    @TempDir
    Path dir;

    private static final String F2_TASK =
            "Implement the Reserve action for a scheduled job; respect the travel buffer and the booking overlap rule";

    private MemoryStore seeded(String branch) {
        MemoryStore store = new JsonFileMemoryStore(dir.resolve("seed-" + branch + ".json"));
        try (WritePath w = new WritePath(store, Namespaces.forBranch(branch))) {
            Seed.proposals().forEach(p -> assertTrue(w.submit(p).join().accepted(), "seed rejected: " + p.content()));
        }
        return store;
    }

    @Test
    void everySeedRecordPassesTheFilter() {
        assertEquals(Seed.proposals().size(), seeded("b1").size());
    }

    @Test
    void feature1DecisionComesBackForAFeature2TaskWithinBudget() {
        MemoryStore store = seeded("day5");
        ReadPath.Result r = new ReadPath(store, Namespaces.forBranch("day5"), new TokenBudget(500)).read(F2_TASK);
        assertTrue(r.referenceIds().contains("mem-001"), "45-minute buffer fact: " + r.referenceIds());
        assertTrue(r.referenceIds().contains("mem-002"), "overlap decision: " + r.referenceIds());
        assertTrue(r.tokensUsed() <= 500, "tokens " + r.tokensUsed());
        assertFalse(r.injected().isBlank());
    }

    @Test
    void outputNeverExceedsTheCeilingEvenWithHundredsOfMatches() {
        MemoryStore store = new JsonFileMemoryStore(dir.resolve("big.json"));
        String ns = Namespaces.forBranch("b1").mainNamespace();
        IntStream.rangeClosed(1, 120).forEach(i -> store.put(new MemoryRecord(
                String.format("mem-%03d", i), ns, "feature-2/adr", String.format("2026-11-22T10:%02d:00Z", i % 60),
                "decision",
                "Reserve action number " + i + " must check stock and the request ID before it saves a reservation row.",
                MemoryRecord.ACTIVE, null)));
        for (int ceiling : new int[] {500, 200, 100}) {
            ReadPath.Result r = new ReadPath(store, Namespaces.forBranch("b1"), new TokenBudget(ceiling))
                    .read("Reserve stock with the request ID");
            assertEquals(r.tokensUsed(), TokenBudget.estimate(r.injected()));
            assertTrue(r.tokensUsed() <= ceiling, "ceiling " + ceiling + " used " + r.tokensUsed());
        }
    }

    @Test
    void irrelevantTaskInjectsNothing() {
        MemoryStore store = seeded("b1");
        ReadPath.Result r = new ReadPath(store, Namespaces.forBranch("b1"), new TokenBudget(500))
                .read("Change the colour of the Angular login button");
        assertTrue(r.injected().isEmpty());
        assertEquals(0, r.tokensUsed());
    }

    @Test
    void anotherBranchsMemoryDoesNotLeak() {
        MemoryStore store = new JsonFileMemoryStore(dir.resolve("shared.json"));
        try (WritePath a = new WritePath(store, Namespaces.forBranch("branch-a"));
             WritePath b = new WritePath(store, Namespaces.forBranch("branch-b"))) {
            a.submit(new Proposal("a/task", "decision",
                    "Branch A only: reservations use a per-part lock for stock checks.", Scope.BRANCH)).join();
            b.submit(new Proposal("shared/rule", "decision",
                    "Shared on main: stock checks must never let availability go below zero.", Scope.MAIN)).join();
        }
        String task = "stock checks reservations availability lock";
        ReadPath.Result onB = new ReadPath(store, Namespaces.forBranch("branch-b"), new TokenBudget(500)).read(task);
        ReadPath.Result onA = new ReadPath(store, Namespaces.forBranch("branch-a"), new TokenBudget(500)).read(task);
        assertFalse(onB.injected().contains("Branch A only"), "branch A leaked into branch B");
        assertTrue(onB.injected().contains("Shared on main"), "main must be visible on branch B");
        assertTrue(onA.injected().contains("Branch A only"));
        assertTrue(onA.injected().contains("Shared on main"));
    }

    @Test
    void supersededRecordIsNeverInjected() {
        MemoryStore store = new JsonFileMemoryStore(dir.resolve("s.json"));
        try (WritePath w = new WritePath(store, Namespaces.forBranch("b1"))) {
            String oldId = w.submit(new Proposal("t", "decision",
                    "Reservations expire after fifteen minutes automatically.", Scope.MAIN)).join().id();
            w.submit(new Proposal("t", "decision",
                    "Reservations never expire; they are held until the job is cancelled.", Scope.MAIN, oldId)).join();
        }
        ReadPath.Result r = new ReadPath(store, Namespaces.forBranch("b1"), new TokenBudget(500))
                .read("reservations expire");
        assertFalse(r.injected().contains("fifteen minutes"));
        assertTrue(r.injected().contains("never expire"));
    }

    @Test
    void newerRecordWinsWhenRelevanceIsEqual() {
        MemoryStore store = new JsonFileMemoryStore(dir.resolve("r.json"));
        String ns = Namespaces.forBranch("b1").mainNamespace();
        store.put(new MemoryRecord("mem-001", ns, "t", "2026-11-20T10:00:00Z", "decision",
                "Reservation critic runs before the final status change.", MemoryRecord.ACTIVE, null));
        store.put(new MemoryRecord("mem-002", ns, "t", "2026-11-22T10:00:00Z", "decision",
                "Reservation critic runs inside the Confirm step only.", MemoryRecord.ACTIVE, null));
        ReadPath.Result r = new ReadPath(store, Namespaces.forBranch("b1"), new TokenBudget(500))
                .read("reservation critic runs");
        assertEquals("mem-002", r.referenceIds().get(0));
    }
}
