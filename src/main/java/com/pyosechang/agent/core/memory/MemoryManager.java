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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Memory manager with unified entry list and m:n visibility via visibleTo.
 * All entries stored in .agent/memory.json with version 2 format.
 * Supports polymorphic MemoryEntry subclasses via Gson TypeAdapters.
 */
public class MemoryManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final MemoryManager INSTANCE = new MemoryManager();

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(MemoryEntry.class, new MemoryEntryTypeAdapter())
        .registerTypeAdapter(MemoryLocation.class, new MemoryLocationTypeAdapter())
        .registerTypeAdapter(com.pyosechang.agent.core.schedule.ScheduleConfig.class, new com.pyosechang.agent.core.schedule.ScheduleConfigTypeAdapter())
        .create();

    private static final double AUTO_LOAD_RADIUS = 32.0;
    private static final int MAX_RECENT_EVENTS = 5;
    private static final int REFERENCE_MAX_DEPTH = 3;
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("@memory:(m\\d+)");

    private final CopyOnWriteArrayList<MemoryEntry> entries = new CopyOnWriteArrayList<>();
    private int nextIdCounter = 1;

    private MemoryManager() {}

    public static MemoryManager getInstance() { return INSTANCE; }

    // --- Persistence ---

    private Path getGlobalPath() {
        return FMLPaths.GAMEDIR.get().resolve(".agent/memory.json");
    }

    public void load() {
        Path path = getGlobalPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);

                List<MemoryEntry> loaded;
                if (root.isJsonObject() && root.getAsJsonObject().has("version")) {
                    // Version 2 format: {"version": 2, "entries": [...]}
                    JsonArray entriesArr = root.getAsJsonObject().getAsJsonArray("entries");
                    loaded = GSON.fromJson(entriesArr, new TypeToken<List<MemoryEntry>>(){}.getType());
                } else if (root.isJsonArray()) {
                    // Version 1 format: bare array (old format)
                    loaded = GSON.fromJson(root, new TypeToken<List<MemoryEntry>>(){}.getType());
                    LOGGER.info("Migrating memory.json from v1 (bare array) to v2 format");
                } else {
                    loaded = null;
                }

                if (loaded != null) {
                    entries.clear();
                    entries.addAll(loaded);
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

        save();
    }

    private void migrateAgentMemory(String agentName, Path agentMemoryFile) {
        try (Reader reader = Files.newBufferedReader(agentMemoryFile, StandardCharsets.UTF_8)) {
            List<MemoryEntry> agentEntries = GSON.fromJson(reader,
                new TypeToken<List<MemoryEntry>>(){}.getType());
            if (agentEntries == null || agentEntries.isEmpty()) {
                deleteFile(agentMemoryFile);
                return;
            }

            Set<String> existingIds = entries.stream()
                .map(MemoryEntry::getId)
                .collect(Collectors.toSet());

            int migrated = 0;
            for (MemoryEntry entry : agentEntries) {
                if (existingIds.contains(entry.getId())) continue;
                if (entry.getVisibleTo().isEmpty()) {
                    entry.setVisibleTo(List.of(agentName));
                }
                entries.add(entry);
                existingIds.add(entry.getId());
                int num = parseIdNumber(entry.getId());
                if (num >= nextIdCounter) nextIdCounter = num + 1;
                migrated++;
            }

            LOGGER.info("Migrated {} entries from agent '{}' memory file", migrated, agentName);
            deleteFile(agentMemoryFile);
        } catch (Exception e) {
            LOGGER.error("Failed to migrate agent memory for '{}'", agentName, e);
        }
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
        Path path = getGlobalPath();
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                JsonObject root = new JsonObject();
                root.addProperty("version", 2);
                root.add("entries", GSON.toJsonTree(entries, new TypeToken<List<MemoryEntry>>(){}.getType()));
                GSON.toJson(root, writer);
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

    /** Create a memory entry from a JSON object (category determines subclass). */
    public synchronized MemoryEntry createFromJson(JsonObject json) {
        // Ensure category is set for type adapter dispatch
        if (!json.has("category")) json.addProperty("category", "event");
        MemoryEntry entry = GSON.fromJson(json, MemoryEntry.class);
        entry.setId(nextId());
        String now = java.time.Instant.now().toString();
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        entries.add(entry);
        save();
        return entry;
    }

    /** Backward-compatible create for schedule and HTTP API. */
    public synchronized MemoryEntry create(String title, String description, String content,
                                            String category, MemoryLocation location,
                                            List<String> visibleTo) {
        JsonObject json = new JsonObject();
        json.addProperty("title", title);
        json.addProperty("description", description);
        if (content != null) json.addProperty("content", content);
        json.addProperty("category", category != null ? category : "event");
        if (location != null) {
            json.add("location", GSON.toJsonTree(location, MemoryLocation.class));
        }
        if (visibleTo != null && !visibleTo.isEmpty()) {
            JsonArray vt = new JsonArray();
            for (String v : visibleTo) vt.add(v);
            json.add("visibleTo", vt);
        }
        return createFromJson(json);
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
        if (fields.has("location") && entry instanceof Locatable) {
            MemoryLocation loc = GSON.fromJson(fields.get("location"), MemoryLocation.class);
            setLocationOnEntry(entry, loc);
        }
        if (fields.has("visible_to")) {
            List<String> vt = new ArrayList<>();
            for (JsonElement el : fields.getAsJsonArray("visible_to")) {
                vt.add(el.getAsString());
            }
            entry.setVisibleTo(vt);
        }
        // Schedule-specific: config update
        if (fields.has("config") && entry instanceof ScheduleMemory sm) {
            sm.setConfig(GSON.fromJson(fields.get("config"), com.pyosechang.agent.core.schedule.ScheduleConfig.class));
        }
        entry.markUpdated();
        save();
        return entry;
    }

    private void setLocationOnEntry(MemoryEntry entry, MemoryLocation loc) {
        if (entry instanceof StorageMemory sm) {
            sm.setLocation(loc);
        } else if (entry instanceof FacilityMemory fm) {
            fm.setLocation(loc);
        } else if (entry instanceof AreaMemory am && loc instanceof AreaLocation al) {
            am.setLocation(al);
        } else if (entry instanceof EventMemory em) {
            em.setLocation(loc);
        }
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

    public List<MemoryEntry> search(String query, String category) {
        return search(query, category, (String) null);
    }

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
            if (e instanceof Locatable loc && loc.getLocation() != null) {
                dist = loc.getLocation().distanceTo(agentX, agentY, agentZ);
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

    public List<Map.Entry<MemoryEntry, Double>> getAllForTitleIndex(double agentX, double agentY, double agentZ) {
        return getAllForTitleIndex(agentX, agentY, agentZ, null);
    }

    /**
     * Get entries whose content should be auto-loaded (filtered by agent visibility).
     * Includes @reference recursive resolution.
     */
    public List<MemoryEntry> getAutoLoadContent(double agentX, double agentY, double agentZ, String agentName) {
        List<MemoryEntry> merged = getMergedEntries(agentName);
        Set<String> loadedIds = new HashSet<>();
        List<MemoryEntry> result = new ArrayList<>();

        // 1. storage/facility/area -> within 32 blocks
        for (MemoryEntry e : merged) {
            if (e instanceof ScheduleMemory) continue;
            if (loadedIds.contains(e.getId())) continue;
            String cat = e.getCategory();
            if (("storage".equals(cat) || "facility".equals(cat) || "area".equals(cat))
                    && e instanceof Locatable loc && loc.getLocation() != null
                    && loc.getLocation().isWithinRange(agentX, agentY, agentZ, AUTO_LOAD_RADIUS)) {
                e.markLoaded();
                result.add(e);
                loadedIds.add(e.getId());
            }
        }

        // 2. event -> latest 5 by updatedAt
        List<MemoryEntry> events = merged.stream()
            .filter(e -> "event".equals(e.getCategory()))
            .filter(e -> !loadedIds.contains(e.getId()))
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
            loadedIds.add(e.getId());
        }

        // 3. @reference resolution — scan ALL visible entries for @memory:mXXX
        //    (not just auto_loaded — schedules, skills etc. can reference other memories)
        resolveReferences(merged, result, loadedIds, 0);

        if (!result.isEmpty()) {
            save();
        }

        return result;
    }

    /**
     * Scan ALL visible entries for @memory:mXXX references.
     * Referenced entries are added to auto_loaded result.
     * Recursive: newly added entries are also scanned (depth limited, cycle safe).
     *
     * @param allEntries all visible entries to scan for references
     * @param result     auto_loaded result list (mutable, references added here)
     * @param loadedIds  already loaded IDs (mutable, prevents cycles)
     * @param depth      current recursion depth
     */
    private void resolveReferences(List<MemoryEntry> allEntries, List<MemoryEntry> result,
                                    Set<String> loadedIds, int depth) {
        if (depth >= REFERENCE_MAX_DEPTH) return;

        List<String> newIds = new ArrayList<>();
        // Scan all visible entries (not just auto_loaded) for references
        List<MemoryEntry> toScan = depth == 0 ? allEntries : new ArrayList<>(result);
        for (MemoryEntry e : toScan) {
            if (e.getContent() == null) continue;
            Matcher matcher = REFERENCE_PATTERN.matcher(e.getContent());
            while (matcher.find()) {
                String refId = matcher.group(1);
                if (!loadedIds.contains(refId)) {
                    newIds.add(refId);
                }
            }
        }

        if (newIds.isEmpty()) return;

        List<MemoryEntry> newEntries = new ArrayList<>();
        for (String refId : newIds) {
            MemoryEntry ref = get(refId);
            if (ref != null && !loadedIds.contains(refId)) {
                ref.markLoaded();
                newEntries.add(ref);
                loadedIds.add(refId);
            }
        }

        if (!newEntries.isEmpty()) {
            result.addAll(newEntries);
            // Depth 1+: only scan newly added entries
            resolveReferences(allEntries, result, loadedIds, depth + 1);
        }
    }

    public List<MemoryEntry> getAutoLoadContent(double agentX, double agentY, double agentZ) {
        return getAutoLoadContent(agentX, agentY, agentZ, null);
    }

    // --- JSON serialization helpers ---

    public JsonObject entryToJson(MemoryEntry entry) {
        JsonObject obj = GSON.toJsonTree(entry, MemoryEntry.class).getAsJsonObject();
        // Ensure visibleTo and scope are present
        if (!obj.has("visibleTo") || obj.get("visibleTo").isJsonNull()) {
            obj.add("visibleTo", new JsonArray());
        }
        JsonArray vt = new JsonArray();
        for (String name : entry.getVisibleTo()) {
            vt.add(name);
        }
        obj.add("visible_to", vt);
        obj.addProperty("scope", entry.getScope());
        // Include location at top level for backward compat
        if (entry instanceof Locatable loc && loc.getLocation() != null) {
            obj.add("location", GSON.toJsonTree(loc.getLocation(), MemoryLocation.class));
        }
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

    /** Get the configured Gson instance for external use. */
    public static Gson getGson() { return GSON; }

    public int size() {
        return entries.size();
    }
}
