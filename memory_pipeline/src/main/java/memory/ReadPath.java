package memory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read path, called just before the LLM call for a task (never eagerly at session start).
 * 1) build a query from the task text  2) search own branch + main, active only
 * 3) rank by relevance (keyword hits), type, and recency; the branch-specific fact wins ties
 * 4) inject progressively: one-line references first, full detail for the top records
 * 5) count tokens BEFORE adding each line and stop at the budget (truncate, never overflow).
 */
public class ReadPath {

    public record Result(String injected, List<String> referenceIds, List<String> detailIds,
                         int tokensUsed, int ceiling) { }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "into", "are", "not", "will", "was", "has",
            "have", "its", "any", "all", "can", "use", "new", "per", "than", "then", "only", "also", "but",
            "should", "must", "does", "did", "via", "when", "which", "while", "each", "such", "out");

    private final MemoryStore store;
    private final Namespaces namespaces;
    private final TokenBudget budget;

    public ReadPath(MemoryStore store, Namespaces namespaces, TokenBudget budget) {
        this.store = store;
        this.namespaces = namespaces;
        this.budget = budget;
    }

    public Result read(String task) {
        Set<String> query = terms(task);
        List<MemoryRecord> candidates = store.search(namespaces.readNamespaces(), true);
        List<MemoryRecord> ranked = rank(candidates, query);

        StringBuilder refs = new StringBuilder("Memory references (JIT):\n");
        List<String> refIds = new ArrayList<>();
        int refsUsed = TokenBudget.estimate(refs.toString());
        for (MemoryRecord r : ranked) {
            String line = "- " + r.id() + " (" + r.typeTag() + "): " + title(r.content()) + "\n";
            int cost = TokenBudget.estimate(line);
            if (refsUsed + cost > budget.referencesBudget()) {
                break;
            }
            refs.append(line);
            refsUsed += cost;
            refIds.add(r.id());
        }

        StringBuilder detail = new StringBuilder("Memory detail:\n");
        List<String> detailIds = new ArrayList<>();
        int detailUsed = TokenBudget.estimate(detail.toString());
        for (MemoryRecord r : ranked) {
            String line = "- " + r.id() + ": " + r.content() + "\n";
            int cost = TokenBudget.estimate(line);
            int room = budget.detailBudget() - detailUsed;
            if (cost > room) {
                // truncate the last item to what fits, if a useful amount fits; then stop
                int chars = (room - 2) * 4;
                if (chars >= 40) {
                    String cut = "- " + r.id() + ": " + r.content();
                    cut = cut.substring(0, Math.min(cut.length(), chars - 4)) + "...\n";
                    if (detailUsed + TokenBudget.estimate(cut) <= budget.detailBudget()) {
                        detail.append(cut);
                        detailIds.add(r.id());
                    }
                }
                break;
            }
            detail.append(line);
            detailUsed += cost;
            detailIds.add(r.id());
        }

        if (refIds.isEmpty() && detailIds.isEmpty()) {
            return new Result("", List.of(), List.of(), 0, budget.ceiling());
        }
        String injected = refs + detail.toString();
        int tokens = TokenBudget.estimate(injected);
        if (tokens > budget.ceiling()) {
            throw new IllegalStateException("Token ceiling breached: " + tokens + " > " + budget.ceiling());
        }
        return new Result(injected, refIds, detailIds, tokens, budget.ceiling());
    }

    private List<MemoryRecord> rank(List<MemoryRecord> candidates, Set<String> query) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<MemoryRecord> byAge = candidates.stream()
                .sorted(Comparator.comparing(MemoryRecord::timestamp).thenComparing(MemoryRecord::id))
                .collect(Collectors.toList());
        int n = byAge.size();
        String branchNs = namespaces.branchNamespace();

        record Scored(MemoryRecord r, double score, int specific) { }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            MemoryRecord r = byAge.get(i);
            Set<String> recTerms = terms(r.content() + " " + r.sourceTask());
            int hits = 0;
            for (String q : query) {
                if (recTerms.contains(q)) {
                    hits++;
                }
            }
            if (hits == 0) {
                continue; // not relevant to this task: never injected "just in case"
            }
            double recency = n == 1 ? 2.0 : 2.0 * i / (n - 1);
            double score = hits * 10 + typeWeight(r.typeTag()) + recency;
            scored.add(new Scored(r, score, r.namespace().equals(branchNs) ? 1 : 0));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Comparator.comparingInt(Scored::specific).reversed())
                .thenComparing(s -> s.r().timestamp(), Comparator.reverseOrder())
                .thenComparing(s -> s.r().id(), Comparator.reverseOrder()));
        return scored.stream().map(Scored::r).collect(Collectors.toList());
    }

    private static int typeWeight(String type) {
        return switch (type) {
            case MemoryRecord.DECISION -> 3;
            case MemoryRecord.FACT -> 2;
            default -> 1;
        };
    }

    private static String title(String content) {
        String[] words = content.split("\\s+");
        String t = String.join(" ", Arrays.copyOf(words, Math.min(6, words.length)));
        return words.length > 6 ? t + "..." : t;
    }

    static Set<String> terms(String text) {
        Set<String> out = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() < 3 || STOP_WORDS.contains(w)) {
                continue;
            }
            if (w.length() > 4 && w.endsWith("s") && !w.endsWith("ss")) {
                w = w.substring(0, w.length() - 1);
            }
            out.add(w);
        }
        return out;
    }
}
