package memory;

/**
 * Token ceiling for injected memory. The estimate is deliberately simple and conservative
 * (about 4 characters per token, rounded up); it is an assumption, not an exact tokenizer.
 *
 * Split (as in session-04/memory-architecture.md, for a 500 ceiling): references 20%, detail 60%,
 * reserve 20% that is never used.
 */
public final class TokenBudget {

    public static final int DEFAULT_CEILING = 500;
    public static final int MAX_RECORD_TOKENS = 60;

    private final int ceiling;

    public TokenBudget(int ceiling) {
        if (ceiling <= 0) {
            throw new IllegalArgumentException("ceiling must be positive");
        }
        this.ceiling = ceiling;
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0);
    }

    public int ceiling() {
        return ceiling;
    }

    public int referencesBudget() {
        return ceiling * 20 / 100;
    }

    public int detailBudget() {
        return ceiling * 60 / 100;
    }
}
