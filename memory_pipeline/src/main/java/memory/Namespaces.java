package memory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Scoping as designed in session-04/memory-architecture.md: repo:branch:env.
 * A branch reads its own namespace plus main, and never another branch's.
 */
public final class Namespaces {

    private final String repo;
    private final String branch;
    private final String env;

    public Namespaces(String repo, String branch, String env) {
        this.repo = repo;
        this.branch = branch;
        this.env = env;
    }

    public static Namespaces forBranch(String branch) {
        return new Namespaces("serviceforge", branch, "dev");
    }

    public String mainNamespace() {
        return repo + ":main:" + env;
    }

    public String branchNamespace() {
        return repo + ":" + branch + ":" + env;
    }

    /** Own branch first (more specific), then main. */
    public Set<String> readNamespaces() {
        Set<String> result = new LinkedHashSet<>();
        result.add(branchNamespace());
        result.add(mainNamespace());
        return result;
    }
}
