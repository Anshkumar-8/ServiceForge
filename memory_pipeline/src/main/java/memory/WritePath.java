package memory;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Write path. Runs AFTER an agent turn on a worker thread, so the live call is never blocked:
 * submit() returns a future at once; the filter and the file write happen in the background.
 * The agent proposes a decision or fact; this class filters it and attaches provenance at write time.
 */
public class WritePath implements AutoCloseable {

    /** Where a record is stored: shared durable memory (main) or this branch only. */
    public enum Scope { MAIN, BRANCH }

    public record Proposal(String sourceTask, String typeTag, String content, Scope scope, String supersedesId) {
        public Proposal(String sourceTask, String typeTag, String content, Scope scope) {
            this(sourceTask, typeTag, content, scope, null);
        }
    }

    public record WriteResult(boolean accepted, String reason, String id) { }

    private final MemoryStore store;
    private final RetainDiscardFilter filter;
    private final Namespaces namespaces;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-write-path");
        t.setDaemon(true);
        return t;
    });

    public WritePath(MemoryStore store, RetainDiscardFilter filter, Namespaces namespaces, Clock clock) {
        this.store = store;
        this.filter = filter;
        this.namespaces = namespaces;
        this.clock = clock;
    }

    public WritePath(MemoryStore store, Namespaces namespaces) {
        this(store, new RetainDiscardFilter(), namespaces, Clock.systemUTC());
    }

    /** Non-blocking: returns immediately. */
    public CompletableFuture<WriteResult> submit(Proposal proposal) {
        return CompletableFuture.supplyAsync(() -> write(proposal), worker);
    }

    /** Waits until everything submitted so far has been written. Used by tests and the CLI. */
    public void flush() {
        CompletableFuture.supplyAsync(() -> null, worker).join();
    }

    private WriteResult write(Proposal p) {
        RetainDiscardFilter.Decision decision = filter.check(p.sourceTask(), p.typeTag(), p.content());
        if (!decision.keep()) {
            return new WriteResult(false, decision.reason(), null);
        }
        if (p.supersedesId() != null) {
            Optional<MemoryRecord> old = store.find(p.supersedesId());
            if (old.isEmpty()) {
                return new WriteResult(false, "cannot supersede unknown record " + p.supersedesId(), null);
            }
        }
        String namespace = p.scope() == Scope.MAIN ? namespaces.mainNamespace() : namespaces.branchNamespace();
        String id = String.format("mem-%03d", store.size() + 1);
        MemoryRecord record = new MemoryRecord(id, namespace, p.sourceTask(), Instant.now(clock).toString(),
                p.typeTag(), p.content().strip(), MemoryRecord.ACTIVE, null);
        store.put(record);
        if (p.supersedesId() != null) {
            store.markSuperseded(p.supersedesId(), id);
        }
        return new WriteResult(true, "stored", id);
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
