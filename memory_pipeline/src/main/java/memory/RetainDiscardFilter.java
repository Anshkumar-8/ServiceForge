package memory;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * B1 retain/discard rule, enforced in code: keep only atomic decisions, facts and context that would
 * change what an agent does next. Reject raw transcript, logs, stack traces, chatter and anything oversize.
 */
public class RetainDiscardFilter {

    public record Decision(boolean keep, String reason) {
        static Decision keepIt() {
            return new Decision(true, "ok");
        }

        static Decision reject(String reason) {
            return new Decision(false, reason);
        }
    }

    private static final Set<String> TYPES =
            Set.of(MemoryRecord.DECISION, MemoryRecord.FACT, MemoryRecord.CONTEXT);

    private static final int MIN_CHARS = 15;

    private static final Pattern LOG_LINE = Pattern.compile(
            "^\\s*(\\d{4}-\\d{2}-\\d{2}|\\[?(INFO|WARN|WARNING|ERROR|DEBUG|TRACE)\\b).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STACK_TRACE = Pattern.compile(
            "(Exception in thread|Caused by:|\\bat [\\w.$]+\\([\\w]+\\.java:\\d+\\))");
    private static final Pattern CHATTER = Pattern.compile(
            "^\\s*(hi|hello|hey|sure|okay|ok|thanks|thank you)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRANSCRIPT = Pattern.compile(
            "(?m)^\\s*(user|assistant|human|ai)\\s*:", Pattern.CASE_INSENSITIVE);

    public Decision check(String sourceTask, String typeTag, String content) {
        if (sourceTask == null || sourceTask.isBlank()) {
            return Decision.reject("missing source task (provenance)");
        }
        if (typeTag == null || !TYPES.contains(typeTag)) {
            return Decision.reject("type must be decision, fact or context");
        }
        if (content == null || content.isBlank()) {
            return Decision.reject("empty content");
        }
        String text = content.strip();
        if (text.length() < MIN_CHARS) {
            return Decision.reject("too short to be a useful fact");
        }
        if (text.contains("\n") || text.contains("\r")) {
            return Decision.reject("multi-line content looks like a dump; write one sentence");
        }
        if (TokenBudget.estimate(text) > TokenBudget.MAX_RECORD_TOKENS) {
            return Decision.reject("over " + TokenBudget.MAX_RECORD_TOKENS + " tokens; split into atomic facts");
        }
        if (LOG_LINE.matcher(text).matches()) {
            return Decision.reject("looks like a log line");
        }
        if (STACK_TRACE.matcher(text).find()) {
            return Decision.reject("looks like a stack trace");
        }
        if (TRANSCRIPT.matcher(text).find()) {
            return Decision.reject("looks like a raw transcript");
        }
        if (CHATTER.matcher(text).matches()) {
            return Decision.reject("conversational chatter");
        }
        return Decision.keepIt();
    }
}
