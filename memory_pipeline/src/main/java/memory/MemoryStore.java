package memory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The only way the pipeline touches storage. Swap the implementation (JSON file, SQL, vector)
 * without changing the write path or the read path.
 */
public interface MemoryStore {

    /** Adds a record. Throws IllegalArgumentException if the id already exists. */
    void put(MemoryRecord record);

    Optional<MemoryRecord> find(String id);

    /** Records in any of the given namespaces; only active ones when activeOnly is true. */
    List<MemoryRecord> search(Collection<String> namespaces, boolean activeOnly);

    /** Marks an existing record superseded (kept for history, never returned as active). */
    void markSuperseded(String id, String supersededById);

    int size();
}
