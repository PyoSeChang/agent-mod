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
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Memory manager with unified entry list and m:n visibility via visibleTo.
 * All entries stored in .agent/memory.json.
 * On first load, migrates per-agent files from .agent/agents/{name}/memory.json.
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

    private Path getGlobalPath() {
        return FMLPaths.GAMEDIR.get().resolve(".agent/memory.json");
    }

    public void load() {
        // Load main memory file
        loadFromFile(getGlobalPath(), entries);
        LOGGER.info("Loaded {} memory entries from global file", entries.size());

        // Migrate per-agent memory files
        Path agentsDir = FMLPaths.GAMEDIR.get().resolve(".agent/agents");
        if (Files.isDirectory(agentsDir)) {
            try (var dirs = Files.list(agentsDir)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    String agentName = dir.getFileName().toString();
                    Path agentMemoryFile = dir.resolve("memory.json");
                    if (Files.exists(agentMemoryFile)) {
                        migrateAgentMemory(agentName, agentMemoryFile);
                    }
                });
            } catch (IOException e) {
                LOGGER.error("Failed to scan agents directory for migration", e);
            }
        }

        // Migrate any entries that have old scope field but no visibleTo
        for (MemoryEntry entry : entries) {
            if (entry.getVisibleTo().isEmpty()) {
                // Check if the transient scope field was deserialized (backward compat)
                // Gson would have set the scope field directly; use setScope to convert
                // We need to re-derive visibleTo from scope if scope was set by deserialization
                // The scope field is kept in JSON for backward compat
                // After deserialization, if visibleTo is null/empty but scope is agent:X,
                // we need to convert
                try {
                    java.lang.reflect.Field scopeField = MemoryEntry.class.getDeclaredField("scope");
                    scopeField.setAccessible(true);
                    String rawScope = (String) scopeField.get(entry);
                    if (rawScope != null && !rawScope.equals("global")) {
                        entry.setScope(rawScope); // this converts to visibleTo
                    }
                } catch (Exception ignored) {}
            }
        }

        save();
    }

    private void migrateAgentMemory(String agentName, Path agentMemoryFile) {
        CopyOnWriteArrayList<MemoryEntry> agentEntries = new CopyOnWriteArrayList<>();
        loadFromFile(agentMemoryFile, agentEntries);

        if (agentEntries.isEmpty()) {
            deleteFile(agentMemoryFile);
            return;
        }

        // Collect existing IDs to skip duplicates
        Set<String> existingIds = entries.stream()
            .map(MemoryEntry::getId)
            .collect(Collectors.toSet());

        int migrated = 0;
        for (MemoryEntry entry : agentEntries) {
            if (existingIds.contains(entry.getId())) continue;

            // Set visibleTo if not already set
            if (entry.getVisibleTo().isEmpty() && !entry.isGlobal()) {
                // Already scoped — keep it
            } else if (entry.getVisibleTo().isEmpty()) {
                // Was in per-agent file but has no explicit scope — assign to this agent
                entry.setVisibleTo(List.of(agentName));
            }
            // If visibleTo already set, respect it

            entries.add(entry);
            existingIds.add(entry.getId());
            migrated++;
        }

        LOGGER.info("Migrated {} entries from agent '{}' memory file", migrated, agentName);
        deleteFile(agentMemoryFile);
    }

    private void deleteFile(Path path) {
        try {
            Files.deleteIfExists(path);
            LOGGER.info("Deleted migrated file: {}", path);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete migrated file: {}", path, e);
        }
    }

    public void save() {
        saveToFile(getGlobalPath(), entries);
    }

    private void loadFromFile(Path path, CopyOnWriteArrayList<MemoryEntry> target) {
        if (!Files.exists(path)) return;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<MemoryEntry> loaded = GSON.fromJson(reader,
                new TypeToken<List<MemoryEntry>>(){}.getType());
            if (loaded != null) {
                target.clear();
                target.addAll(loaded);
                for (MemoryEntry e : loaded) {
                    int num = parseIdNumber(e.getId());
                    if (num >= nextIdCounter) {
                        nextIdCounter = num + 1;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load memory from {}", path, e);
        }
    }

    private void saveToFile(Path path, CopyOnWriteArrayList<MemoryEntry> source) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(source, writer);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("Failed to save memory to {}", path, e);
        }
    }

    private synchronized String nextId() {
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

    /** Get merged entries visible to a specific agent (global + agent-specific) */
    public List<MemoryEntry> getMergedEntries(String agentName) {
        if (agentName == null || agentName.isEmpty()) {
            return new ArrayList<>(entries);
        }
        return entries.stream()
            .filter(e -> e.isVisibleTo(agentName))
            .collect(Collectors.toList());
    }

    // --- CRUD ---

    public synchronized MemoryEntry create(String title, String description, String content,
                                            String category, List<String> tags,
                                            MemoryLocation location, String scope) {
        return create(title, description, content, category, tags, location, scope, null);
    }

    public synchronized MemoryEntry create(String title, String description, String content,
                                            String category, List<String> tags,
                                            MemoryLocation location, String scope,
                                            List<String> visibleTo) {
        MemoryEntry entry = new MemoryEntry();
        entry.setId(nextId());
        entry.setTitle(title);
        entry.setDescription(description);
        entry.setContent(content);
        entry.setCategory(category != null ? category : "event");
        entry.setTags(tags);
        entry.setLocation(location);

        // visibleTo takes precedence over scope
        if (visibleTo != null) {
            entry.setVisibleTo(visibleTo);
        } else if (scope != null) {
            entry.setScope(scope);
        }
        // default: visibleTo is empty (global)

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
        if (fields.has("visible_to")) {
            List<String> vt = new ArrayList<>();
            for (JsonElement el : fields.getAsJsonArray("visible_to")) {
                vt.add(el.getAsString());
            }
            entry.setVisibleTo(vt);
        } else if (fields.has("scope")) {
            entry.setScope(fields.get("scope").getAsString());
        }
        entry.markUpdated();
        save();
        return entry;
    }

    public synchronized boolean delete(String id) {
        boolean removed = entries.removeIf(e -> e.getId().equals(id));
        if (removed) {
            save();
        }
        return removed;
    }

    /**
     * Search with optional scope filtering.
     * @param scope null or "all" = all entries,
     *              "global" = entries where isGlobal(),
     *              "agent:name" = entries visible to that agent (includes global),
     *              "only:name" = entries where visibleTo contains name (excludes global)
     */
    public List<MemoryEntry> search(String query, String category, String scope) {
        Stream<MemoryEntry> stream;
        if (scope == null || scope.isEmpty() || "all".equals(scope)) {
            stream = entries.stream();
        } else if ("global".equals(scope)) {
            stream = entries.stream().filter(MemoryEntry::isGlobal);
        } else if (scope.startsWith("only:")) {
            String name = scope.substring("only:".length());
            stream = entries.stream().filter(e -> e.getVisibleTo().contains(name));
        } else if (scope.startsWith("agent:")) {
            String name = scope.substring("agent:".length());
            stream = entries.stream().filter(e -> e.isVisibleTo(name));
        } else {
            stream = entries.stream();
        }

        return stream
            .filter(e -> category == null || category.isEmpty() || category.equals(e.getCategory()))
            .filter(e -> e.matchesQuery(query))
            .collect(Collectors.toList());
    }

    /** Backward-compatible search (all scopes) */
    public List<MemoryEntry> search(String query, String category) {
        return search(query, category, (String) null);
    }

    /** Search across all scopes explicitly */
    public List<MemoryEntry> searchAll(String query, String category) {
        return search(query, category, (String) null);
    }

    // --- Observation injection ---

    /**
     * Get all entries visible to agent, sorted by distance for the title index.
     */
    public List<Map.Entry<MemoryEntry, Double>> getAllForTitleIndex(double agentX, double agentY, double agentZ, String agentName) {
        List<MemoryEntry> merged = getMergedEntries(agentName);
        List<Map.Entry<MemoryEntry, Double>> result = new ArrayList<>();
        for (MemoryEntry e : merged) {
            double dist = -1;
            if (e.getLocation() != null) {
                dist = e.getLocation().distanceTo(agentX, agentY, agentZ);
            }
            result.add(Map.entry(e, dist));
        }
        result.sort((a, b) -> {
            if (a.getValue() < 0 && b.getValue() < 0) return 0;
            if (a.getValue() < 0) return 1;
            if (b.getValue() < 0) return -1;
            return Double.compare(a.getValue(), b.getValue());
        });
        return result;
    }

    /** Backward-compatible overload */
    public List<Map.Entry<MemoryEntry, Double>> getAllForTitleIndex(double agentX, double agentY, double agentZ) {
        return getAllForTitleIndex(agentX, agentY, agentZ, null);
    }

    /**
     * Get entries whose content should be auto-loaded (filtered by agent visibility).
     */
    public List<MemoryEntry> getAutoLoadContent(double agentX, double agentY, double agentZ, String agentName) {
        List<MemoryEntry> merged = getMergedEntries(agentName);
        List<MemoryEntry> result = new ArrayList<>();

        // 1. preference -> always
        for (MemoryEntry e : merged) {
            if ("preference".equals(e.getCategory())) {
                e.markLoaded();
                result.add(e);
            }
        }

        // 2. storage/facility/area -> within 32 blocks
        for (MemoryEntry e : merged) {
            String cat = e.getCategory();
            if (("storage".equals(cat) || "facility".equals(cat) || "area".equals(cat))
                    && e.getLocation() != null
                    && e.getLocation().isWithinRange(agentX, agentY, agentZ, AUTO_LOAD_RADIUS)) {
                e.markLoaded();
                result.add(e);
            }
        }

        // 3. event -> latest 5 by updatedAt
        List<MemoryEntry> events = merged.stream()
            .filter(e -> "event".equals(e.getCategory()))
            .sorted((a, b) -> {
                String at = a.getUpdatedAt() != null ? a.getUpdatedAt() : "";
                String bt = b.getUpdatedAt() != null ? b.getUpdatedAt() : "";
                return bt.compareTo(at);
            })
            .limit(MAX_RECENT_EVENTS)
            .collect(Collectors.toList());
        for (MemoryEntry e : events) {
            e.markLoaded();
            result.add(e);
        }

        if (!result.isEmpty()) {
            save();
        }

        return result;
    }

    /** Backward-compatible overload */
    public List<MemoryEntry> getAutoLoadContent(double agentX, double agentY, double agentZ) {
        return getAutoLoadContent(agentX, agentY, agentZ, null);
    }

    // --- JSON serialization helpers ---

    public JsonObject entryToJson(MemoryEntry entry) {
        JsonObject obj = GSON.toJsonTree(entry).getAsJsonObject();
        // Ensure visibleTo and scope are present
        if (!obj.has("visibleTo") || obj.get("visibleTo").isJsonNull()) {
            obj.add("visibleTo", new JsonArray());
        }
        // Also include as visible_to for HTTP API consumers
        JsonArray vt = new JsonArray();
        for (String name : entry.getVisibleTo()) {
            vt.add(name);
        }
        obj.add("visible_to", vt);
        // Ensure derived scope is correct
        obj.addProperty("scope", entry.getScope());
        return obj;
    }

    public JsonObject entryToSummaryJson(MemoryEntry entry, double distance) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", entry.getId());
        obj.addProperty("category", entry.getCategory());
        obj.addProperty("title", entry.getTitle());
        obj.addProperty("description", entry.getDescription());
        obj.addProperty("distance", Math.round(distance * 10.0) / 10.0);
        obj.addProperty("scope", entry.getScope());
        JsonArray vt = new JsonArray();
        for (String name : entry.getVisibleTo()) {
            vt.add(name);
        }
        obj.add("visible_to", vt);
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
