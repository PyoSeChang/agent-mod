package com.pyosechang.agent.core.schedule;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pyosechang.agent.core.memory.MemoryEntry;

/**
 * Wrapper around MemoryEntry for schedule-specific typed access.
 * Schedule data is stored in MemoryEntry with category="schedule",
 * and the schedule config is serialized as JSON in the content field.
 */
public class ScheduleEntry {

    public static final String CATEGORY = "schedule";

    private final MemoryEntry memoryEntry;
    private ScheduleConfig config;

    public ScheduleEntry(MemoryEntry memoryEntry) {
        this.memoryEntry = memoryEntry;
        this.config = parseConfig(memoryEntry.getContent());
    }

    /** Create a new ScheduleEntry backed by a new MemoryEntry */
    public static ScheduleEntry create(String id, String title, ScheduleConfig config) {
        MemoryEntry me = new MemoryEntry();
        me.setId(id);
        me.setTitle(title != null ? title : config.getType().name() + " → " + config.getTargetAgent());
        me.setDescription(buildDescription(config));
        me.setCategory(CATEGORY);
        me.setContent(config.toJson().toString());
        return new ScheduleEntry(me);
    }

    /** Check if a MemoryEntry is a schedule entry */
    public static boolean isSchedule(MemoryEntry entry) {
        return CATEGORY.equals(entry.getCategory());
    }

    /** Convert a MemoryEntry to ScheduleEntry (returns null if not a schedule) */
    public static ScheduleEntry fromMemoryEntry(MemoryEntry entry) {
        if (!isSchedule(entry)) return null;
        return new ScheduleEntry(entry);
    }

    public MemoryEntry getMemoryEntry() { return memoryEntry; }
    public String getId() { return memoryEntry.getId(); }

    public ScheduleConfig getConfig() { return config; }

    /** Update config and persist to MemoryEntry content */
    public void updateConfig(ScheduleConfig newConfig) {
        this.config = newConfig;
        memoryEntry.setContent(newConfig.toJson().toString());
        memoryEntry.setDescription(buildDescription(newConfig));
        memoryEntry.markUpdated();
    }

    /** Refresh config from MemoryEntry content (after external update) */
    public void refreshConfig() {
        this.config = parseConfig(memoryEntry.getContent());
    }

    /** Build summary JSON for API responses */
    public JsonObject toSummaryJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", getId());
        obj.addProperty("title", memoryEntry.getTitle());
        obj.addProperty("type", config.getType().name());
        obj.addProperty("target_agent", config.getTargetAgent());
        obj.addProperty("prompt_message", config.getPromptMessage());
        obj.addProperty("enabled", config.isEnabled());
        obj.addProperty("last_triggered_tick", config.getLastTriggeredTick());

        switch (config.getType()) {
            case TIME_OF_DAY -> {
                obj.addProperty("time_of_day", config.getTimeOfDay());
                obj.addProperty("repeat_days", config.getRepeatDays());
            }
            case INTERVAL -> {
                obj.addProperty("interval_ticks", config.getIntervalTicks());
                obj.addProperty("repeat", config.isRepeat());
            }
            case OBSERVER -> {
                obj.addProperty("observer_count", config.getObservers().size());
                obj.addProperty("threshold", config.getThreshold());
            }
        }
        return obj;
    }

    private static ScheduleConfig parseConfig(String content) {
        if (content == null || content.isBlank()) {
            return new ScheduleConfig();
        }
        try {
            JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
            return ScheduleConfig.fromJson(obj);
        } catch (Exception e) {
            return new ScheduleConfig();
        }
    }

    static String buildDescription(ScheduleConfig config) {
        return String.format("Schedule: %s → %s | %s",
            config.getType().name(),
            config.getTargetAgent(),
            config.getPromptMessage().length() > 50
                ? config.getPromptMessage().substring(0, 50) + "..."
                : config.getPromptMessage());
    }
}
