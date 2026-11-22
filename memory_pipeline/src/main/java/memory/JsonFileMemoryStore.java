package memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * File-backed store (a JSON array). Fine for hundreds of short facts; see session-04/memory-architecture.md
 * for when to swap it. All methods are synchronized, and every change is written atomically
 * (temp file, then move) so a crash cannot leave half a file.
 */
public class JsonFileMemoryStore implements MemoryStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, MemoryRecord> records = new LinkedHashMap<>();

    public JsonFileMemoryStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            if (Files.size(file) == 0) {
                return;
            }
            List<MemoryRecord> loaded = mapper.readValue(file.toFile(), new TypeReference<List<MemoryRecord>>() { });
            for (MemoryRecord r : loaded) {
                records.put(r.id(), r);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read memory store " + file, e);
        }
    }

    private void save() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), List.copyOf(records.values()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write memory store " + file, e);
        }
    }

    @Override
    public synchronized void put(MemoryRecord record) {
        if (records.containsKey(record.id())) {
            throw new IllegalArgumentException("Record id already exists: " + record.id());
        }
        records.put(record.id(), record);
        save();
    }

    @Override
    public synchronized Optional<MemoryRecord> find(String id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public synchronized List<MemoryRecord> search(Collection<String> namespaces, boolean activeOnly) {
        return records.values().stream()
                .filter(r -> namespaces.contains(r.namespace()))
                .filter(r -> !activeOnly || MemoryRecord.ACTIVE.equals(r.status()))
                .collect(Collectors.toList());
    }

    @Override
    public synchronized void markSuperseded(String id, String supersededById) {
        MemoryRecord old = records.get(id);
        if (old == null) {
            throw new IllegalArgumentException("No record with id " + id);
        }
        records.put(id, new MemoryRecord(old.id(), old.namespace(), old.sourceTask(), old.timestamp(),
                old.typeTag(), old.content(), MemoryRecord.SUPERSEDED, supersededById));
        save();
    }

    @Override
    public synchronized int size() {
        return records.size();
    }
}
