package com.pyosechang.agent.core.memory;

import com.google.gson.*;
import com.pyosechang.agent.core.schedule.ScheduleConfig;
import com.pyosechang.agent.core.schedule.ScheduleConfigTypeAdapter;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Gson TypeAdapter for MemoryEntry — dispatches to subclass based on "category" field.
 *
 * Deserialization strategy:
 *   1. Create subclass instance via internal Gson (handles subclass-specific fields)
 *   2. Manually fill ALL base MemoryEntry fields from JSON (never trust Gson reflection for parent fields)
 *   3. Handle backward compat (scope migration, old schedule format)
 */
public class MemoryEntryTypeAdapter implements JsonSerializer<MemoryEntry>, JsonDeserializer<MemoryEntry> {

    private final Gson internal;

    public MemoryEntryTypeAdapter() {
        this.internal = new GsonBuilder()
            .registerTypeAdapter(MemoryLocation.class, new MemoryLocationTypeAdapter())
            .registerTypeAdapter(ScheduleConfig.class, new ScheduleConfigTypeAdapter())
            .create();
    }

    @Override
    public JsonElement serialize(MemoryEntry src, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject obj = internal.toJsonTree(src, src.getClass()).getAsJsonObject();
        // Always write base fields explicitly (don't rely on Gson reflection for parent fields)
        obj.addProperty("id", src.getId());
        obj.addProperty("title", src.getTitle());
        obj.addProperty("description", src.getDescription());
        if (src.getContent() != null) obj.addProperty("content", src.getContent());
        obj.addProperty("category", src.getCategory());
        obj.addProperty("scope", src.getScope());
        JsonArray vt = new JsonArray();
        for (String v : src.getVisibleTo()) vt.add(v);
        obj.add("visibleTo", vt);
        if (src.getCreatedAt() != null) obj.addProperty("createdAt", src.getCreatedAt());
        if (src.getUpdatedAt() != null) obj.addProperty("updatedAt", src.getUpdatedAt());
        if (src.getLoadedAt() != null) obj.addProperty("loadedAt", src.getLoadedAt());
        // Include location at top level for Locatable subclasses
        if (src instanceof Locatable loc && loc.getLocation() != null) {
            obj.add("location", internal.toJsonTree(loc.getLocation(), MemoryLocation.class));
        }
        return obj;
    }

    @Override
    public MemoryEntry deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (!json.isJsonObject()) throw new JsonParseException("MemoryEntry must be a JSON object");
        JsonObject obj = json.getAsJsonObject();
        String category = obj.has("category") ? obj.get("category").getAsString() : null;

        // 1. Create subclass instance (handles subclass-specific fields like location, config)
        MemoryEntry entry = switch (category) {
            case "storage" -> internal.fromJson(obj, StorageMemory.class);
            case "facility" -> internal.fromJson(obj, FacilityMemory.class);
            case "area" -> internal.fromJson(obj, AreaMemory.class);
            case "event" -> internal.fromJson(obj, EventMemory.class);
            case "skill" -> internal.fromJson(obj, SkillMemory.class);
            case "schedule" -> deserializeSchedule(obj);
            default -> new MemoryEntry();
        };

        // 2. Fill ALL base fields manually — never rely on Gson reflection for parent fields
        fillBaseFields(entry, obj);

        return entry;
    }

    /** Manually set all MemoryEntry base fields from JSON. */
    private void fillBaseFields(MemoryEntry entry, JsonObject obj) {
        if (obj.has("id")) entry.setId(obj.get("id").getAsString());
        if (obj.has("title")) entry.setTitle(obj.get("title").getAsString());
        if (obj.has("description")) entry.setDescription(obj.get("description").getAsString());
        if (obj.has("content")) entry.setContent(obj.get("content").getAsString());
        if (obj.has("category")) entry.setCategory(obj.get("category").getAsString());
        if (obj.has("createdAt")) entry.setCreatedAt(obj.get("createdAt").getAsString());
        if (obj.has("updatedAt")) entry.setUpdatedAt(obj.get("updatedAt").getAsString());
        if (obj.has("loadedAt")) entry.setLoadedAt(obj.get("loadedAt").getAsString());

        // visibleTo
        if (obj.has("visibleTo") && obj.get("visibleTo").isJsonArray()) {
            List<String> vt = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("visibleTo")) vt.add(el.getAsString());
            entry.setVisibleTo(vt);
        }

        // Backward compat: migrate old scope → visibleTo
        if (entry.getVisibleTo().isEmpty() && obj.has("scope")) {
            String scope = obj.get("scope").getAsString();
            if (scope != null && !"global".equals(scope)) {
                if (scope.startsWith("agents:")) {
                    entry.setVisibleTo(List.of(scope.substring("agents:".length()).split(",")));
                } else if (scope.startsWith("agent:")) {
                    entry.setVisibleTo(List.of(scope.substring("agent:".length())));
                }
            }
        }
    }

    private ScheduleMemory deserializeSchedule(JsonObject obj) {
        ScheduleMemory sm = internal.fromJson(obj, ScheduleMemory.class);

        // Backward compat: if config is null, try parsing from content field (old format)
        if (sm.getConfig() == null) {
            String rawContent = obj.has("content") ? obj.get("content").getAsString() : null;
            if (rawContent != null && !rawContent.isBlank()) {
                try {
                    JsonObject configJson = JsonParser.parseString(rawContent).getAsJsonObject();
                    String promptMessage = configJson.has("prompt_message")
                        ? configJson.get("prompt_message").getAsString() : "";
                    sm.setConfig(ScheduleConfig.fromJson(configJson));
                    sm.setContent(promptMessage);
                } catch (Exception ignored) {}
            }
        }
        // Note: base fields (including content) are filled by fillBaseFields() after this returns
        return sm;
    }
}
