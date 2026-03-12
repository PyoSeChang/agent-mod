package com.pyosechang.agent.core.memory;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Singleton memory manager — CRUD + JSON persistence + distance sorting + auto-load queries.
 * Thread-safe via CopyOnWriteArrayList for concurrent HTTP + server tick access.
 */
public class MemoryManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final MemoryManager INSTANCE = new MemoryManager();

    private static final double AUTO_LOAD_RADIUS = 32.0;
    private static final int MAX_RECENT_EVENTS = 5;

    private final CopyOnWriteArrayList<MemoryEntry> entries = new CopyOnWriteArrayList<>();
    private int nextIdCounter = 1;

    private MemoryManager() {}

    public static MemoryManager getInstance() { return INSTANCE; }

    // --- Persistence ---

    private Path getStoragePath() {
        return FMLPaths.GAMEDIR.get().resolve(".agent/memory.json");
    }

    public void load() {
        Path path = getStoragePath();
        if (!Files.exists(path)) {
            LOGGER.info("No memory file found at {}, starting fresh", path);
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<MemoryEntry> loaded = GSON.fromJson(reader,
                new TypeToken<List<MemoryEntry>>(){}.getType());
            if (loaded != null) {
                entries.clear();
                entries.addAll(loaded);
                // Update ID counter to max existing + 1
                for (MemoryEntry e : entries) {
                    int num = parseIdNumber(e.getId());
                    if (num >= nextIdCounter) {
                        nextIdCounter = num + 1;
                    }
                }
                LOGGER.info("Loaded {} memory entries from {}", entries.size(), path);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load memory from {}", path, e);
        }
    }

    public void save() {
        Path path = getStoragePath();
        try {
            Files.createDirectories(path.getParent());
            // Atomic write: temp file → rename
            Path tmp = path.resolveSibling("memory.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(entries, writer);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("Failed to save memory to {}", path, e);
        }
    }

    private String nextId() {
        return String.format("m%03d", nextIdCounter++);
    }

    private int parseIdNumber(String id) {
        if (id != null && id.startsWith("m")) {
            try {
                return Integer.parseInt(id.substring(1));
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    // --- CRUD ---

    public synchronized MemoryEntry create(String title, String description, String content,
                                            String category, List<String> tags,
                                            MemoryLocation location, String scope) {
        MemoryEntry entry = new MemoryEntry();
        entry.setId(nextId());
        entry.setTitle(title);
        entry.setDescription(description);
        entry.setContent(content);
        entry.setCategory(category != null ? category : "event");
        entry.setTags(tags);
        entry.setLocation(location);
        entry.setScope(scope != null ? scope : "global");
        entries.add(entry);
        save();
        return entry;
    }

    public MemoryEntry get(String id) {
        for (MemoryEntry e : entries) {
            if (e.getId().equals(id)) return e;
        }
        return null;
    }

    public synchronized MemoryEntry update(String id, JsonObject fields) {
        MemoryEntry entry = get(id);
        if (entry == null) return null;

        if (fields.has("title")) entry.setTitle(fields.get("title").getAsString());
        if (fields.has("description")) entry.setDescription(fields.get("description").getAsString());
        if (fields.has("content")) entry.setContent(fields.get("content").getAsString());
        if (fields.has("category")) entry.setCategory(fields.get("category").getAsString());
        if (fields.has("tags")) {
            List<String> tags = new ArrayList<>();
            for (JsonElement el : fields.getAsJsonArray("tags")) {
                tags.add(el.getAsString());
            }
            entry.setTags(tags);
        }
        if (fields.has("location")) {
            entry.setLocation(MemoryLocation.fromJson(fields.getAsJsonObject("location")));
        }
        entry.markUpdated();
        save();
        return entry;
    }

    public synchronized boolean delete(String id) {
        boolean removed = entries.removeIf(e -> e.getId().equals(id));
        if (removed) save();
        return removed;
    }

    public List<MemoryEntry> search(String query, String category) {
        return entries.stream()
            .filter(e -> category == null || category.isEmpty() || category.equals(e.getCategory()))
            .filter(e -> e.matchesQuery(query))
            .collect(Collectors.toList());
    }

    // --- Observation injection ---

    /**
     * Get all entries sorted by distance for the title index.
     * Location-less entries get distance -1 (sorted last).
     */
    public List<Map.Entry<MemoryEntry, Double>> getAllForTitleIndex(double agentX, double agentY, double agentZ) {
        List<Map.Entry<MemoryEntry, Double>> result = new ArrayList<>();
        for (MemoryEntry e : entries) {
            double dist = -1;
            if (e.getLocation() != null) {
                dist = e.getLocation().distanceTo(agentX, agentY, agentZ);
            }
            result.add(Map.entry(e, dist));
        }
        // Sort: entries with location by distance, then entries without location
        result.sort((a, b) -> {
            if (a.getValue() < 0 && b.getValue() < 0) return 0;
            if (a.getValue() < 0) return 1;
            if (b.getValue() < 0) return -1;
            return Double.compare(a.getValue(), b.getValue());
        });
        return result;
    }

    /**
     * Get entries whose content should be auto-loaded based on category rules.
     * Updates loadedAt for returned entries.
     */
    public List<MemoryEntry> getAutoLoadContent(double agentX, double agentY, double agentZ) {
        List<MemoryEntry> result = new ArrayList<>();

        // 1. preference → always
        for (MemoryEntry e : entries) {
            if ("preference".equals(e.getCategory())) {
                e.markLoaded();
                result.add(e);
            }
        }

        // 2. storage/facility/area → within 32 blocks
        for (MemoryEntry e : entries) {
            String cat = e.getCategory();
            if (("storage".equals(cat) || "facility".equals(cat) || "area".equals(cat))
                    && e.getLocation() != null
                    && e.getLocation().isWithinRange(agentX, agentY, agentZ, AUTO_LOAD_RADIUS)) {
                e.markLoaded();
                result.add(e);
            }
        }

        // 3. event → latest 5 by updatedAt
        List<MemoryEntry> events = entries.stream()
            .filter(e -> "event".equals(e.getCategory()))
            .sorted((a, b) -> {
                String at = a.getUpdatedAt() != null ? a.getUpdatedAt() : "";
                String bt = b.getUpdatedAt() != null ? b.getUpdatedAt() : "";
                return bt.compareTo(at); // descending
            })
            .limit(MAX_RECENT_EVENTS)
            .collect(Collectors.toList());
        for (MemoryEntry e : events) {
            e.markLoaded();
            result.add(e);
        }

        // Auto-save after marking loadedAt
        if (!result.isEmpty()) {
            save();
        }

        return result;
    }

    // --- JSON serialization helpers ---

    public JsonObject entryToJson(MemoryEntry entry) {
        return GSON.toJsonTree(entry).getAsJsonObject();
    }

    public JsonObject entryToSummaryJson(MemoryEntry entry, double distance) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", entry.getId());
        obj.addProperty("category", entry.getCategory());
        obj.addProperty("title", entry.getTitle());
        obj.addProperty("description", entry.getDescription());
        obj.addProperty("distance", Math.round(distance * 10.0) / 10.0);
        return obj;
    }

    public JsonObject entryToAutoLoadJson(MemoryEntry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", entry.getId());
        obj.addProperty("title", entry.getTitle());
        obj.addProperty("content", entry.getContent());
        return obj;
    }

    public int size() {
        return entries.size();
    }
}
