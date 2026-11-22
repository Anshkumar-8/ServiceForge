package memory;

import memory.WritePath.Proposal;
import memory.WritePath.Scope;
import memory.WritePath.WriteResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CLI:
 *   seed  &lt;branch&gt;                                   fill an empty store with the hand-written seed
 *   write &lt;branch&gt; MAIN|BRANCH &lt;type&gt; &lt;task&gt; &lt;text&gt;  propose one fact (goes through the filter)
 *   read  &lt;branch&gt; &lt;task description...&gt;             JIT retrieval under the token ceiling
 *   demo                                            end-to-end round trip in a temporary store
 * Store file: -Dmemory.store=path (default memory_store.json); ceiling: -Dmemory.ceiling=500.
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("usage: seed <branch> | write <branch> <MAIN|BRANCH> <type> <task> <text> | read <branch> <task> | demo");
            return;
        }
        int ceiling = Integer.getInteger("memory.ceiling", TokenBudget.DEFAULT_CEILING);
        Path file = Path.of(System.getProperty("memory.store", "memory_store.json"));
        switch (args[0]) {
            case "seed" -> seed(file, args[1]);
            case "write" -> write(file, args);
            case "read" -> read(file, args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)), ceiling);
            case "demo" -> demo(ceiling);
            default -> System.out.println("unknown command " + args[0]);
        }
    }

    private static void seed(Path file, String branch) {
        MemoryStore store = new JsonFileMemoryStore(file);
        if (store.size() > 0) {
            System.out.println("store already has " + store.size() + " records; refusing to seed twice");
            return;
        }
        try (WritePath write = new WritePath(store, Namespaces.forBranch(branch))) {
            report(submitAll(write, Seed.proposals()));
        }
        System.out.println("store now has " + store.size() + " records in " + file);
    }

    private static void write(Path file, String[] a) {
        MemoryStore store = new JsonFileMemoryStore(file);
        try (WritePath write = new WritePath(store, Namespaces.forBranch(a[1]))) {
            Proposal p = new Proposal(a[4], a[3], a[5], Scope.valueOf(a[2]));
            report(submitAll(write, List.of(p)));
        }
    }

    private static void read(Path file, String branch, String task, int ceiling) {
        MemoryStore store = new JsonFileMemoryStore(file);
        ReadPath.Result r = new ReadPath(store, Namespaces.forBranch(branch), new TokenBudget(ceiling)).read(task);
        printResult(task, r);
    }

    private static void demo(int ceiling) throws IOException {
        Path tmp = Files.createTempFile("memory-demo", ".json");
        Files.deleteIfExists(tmp);
        String branch = "day5-memory-pipeline";
        MemoryStore store = new JsonFileMemoryStore(tmp);
        try (WritePath write = new WritePath(store, Namespaces.forBranch(branch))) {
            System.out.println("== 1. Write a Feature 1 decision (after the turn) and some chatter");
            List<Proposal> first = List.of(
                    new Proposal("bugfix-1/overlap", MemoryRecord.DECISION,
                            "Overlap detection compares buffered intervals with a 45-minute travel buffer; "
                                    + "exactly 45 minutes apart is allowed.", Scope.MAIN),
                    new Proposal("chat", MemoryRecord.FACT, "Sure, thanks! Let me know if you need anything else.", Scope.MAIN));
            report(submitAll(write, first));

            System.out.println("\n== 2. Seed the rest (same filter)");
            List<Proposal> rest = new ArrayList<>(Seed.proposals());
            report(submitAll(write, rest));
        }
        System.out.println("\n== 3. Later, working on Feature 2: JIT read under the ceiling");
        ReadPath.Result r = new ReadPath(store, Namespaces.forBranch(branch), new TokenBudget(ceiling))
                .read("Implement the Reserve action for a scheduled job; respect the travel buffer and the booking overlap rule");
        printResult("Feature 2 task", r);
        Files.deleteIfExists(tmp);
    }

    private static List<WriteResult> submitAll(WritePath write, List<Proposal> proposals) {
        List<WriteResult> results = new ArrayList<>();
        proposals.forEach(p -> results.add(write.submit(p).join()));
        write.flush();
        return results;
    }

    private static void report(List<WriteResult> results) {
        for (WriteResult r : results) {
            System.out.println((r.accepted() ? "  stored   " + r.id() : "  REJECTED " + r.reason()));
        }
    }

    private static void printResult(String task, ReadPath.Result r) {
        System.out.println("task: " + task);
        System.out.println(r.injected().isEmpty() ? "(nothing relevant: nothing injected)" : r.injected());
        System.out.println("references=" + r.referenceIds() + " detail=" + r.detailIds());
        System.out.println("tokens used " + r.tokensUsed() + " of ceiling " + r.ceiling());
    }
}
