package memory;

/**
 * One durable memory: a single decision, fact or context sentence plus provenance.
 * Provenance (sourceTask, timestamp, typeTag, status) is attached at write time, never backfilled.
 */
public record MemoryRecord(
        String id,
        String namespace,
        String sourceTask,
        String timestamp,
        String typeTag,
        String content,
        String status,
        String supersededBy) {

    public static final String ACTIVE = "active";
    public static final String SUPERSEDED = "superseded";

    public static final String DECISION = "decision";
    public static final String FACT = "fact";
    public static final String CONTEXT = "context";
}
