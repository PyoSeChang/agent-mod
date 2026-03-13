package com.pyosechang.agent.core.schedule;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Trigger configuration for a schedule entry.
 * Serialized as JSON inside MemoryEntry.content.
 */
public class ScheduleConfig {

    public enum Type {
        TIME_OF_DAY, INTERVAL, OBSERVER
    }

    private Type type;
    private String targetAgent;
    private String promptMessage;
    private boolean enabled = true;
    private long registeredTick;
    private long lastTriggeredTick = -1;
    private long lastTriggeredDay = -1;

    // TIME_OF_DAY fields
    private int timeOfDay;      // 0-23999
    private int repeatDays = 1; // 0=once, N=every N days

    // INTERVAL fields
    private int intervalTicks;  // minimum 20
    private boolean repeat = true;

    // OBSERVER fields
    private List<ObserverDef> observers = new ArrayList<>();
    private int threshold = 1;

    public ScheduleConfig() {}

    // --- Getters/Setters ---

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getTargetAgent() { return targetAgent; }
    public void setTargetAgent(String targetAgent) { this.targetAgent = targetAgent; }

    public String getPromptMessage() { return promptMessage; }
    public void setPromptMessage(String promptMessage) { this.promptMessage = promptMessage; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getRegisteredTick() { return registeredTick; }
    public void setRegisteredTick(long registeredTick) { this.registeredTick = registeredTick; }

    public long getLastTriggeredTick() { return lastTriggeredTick; }
    public void setLastTriggeredTick(long lastTriggeredTick) { this.lastTriggeredTick = lastTriggeredTick; }

    public long getLastTriggeredDay() { return lastTriggeredDay; }
    public void setLastTriggeredDay(long lastTriggeredDay) { this.lastTriggeredDay = lastTriggeredDay; }

    public int getTimeOfDay() { return timeOfDay; }
    public void setTimeOfDay(int timeOfDay) { this.timeOfDay = timeOfDay; }

    public int getRepeatDays() { return repeatDays; }
    public void setRepeatDays(int repeatDays) { this.repeatDays = repeatDays; }

    public int getIntervalTicks() { return intervalTicks; }
    public void setIntervalTicks(int intervalTicks) { this.intervalTicks = intervalTicks; }

    public boolean isRepeat() { return repeat; }
    public void setRepeat(boolean repeat) { this.repeat = repeat; }

    public List<ObserverDef> getObservers() { return observers; }
    public void setObservers(List<ObserverDef> observers) { this.observers = observers != null ? observers : new ArrayList<>(); }

    public int getThreshold() { return threshold; }
    public void setThreshold(int threshold) { this.threshold = threshold; }

    // --- JSON serialization ---

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type.name());
        obj.addProperty("target_agent", targetAgent);
        obj.addProperty("prompt_message", promptMessage);
        obj.addProperty("enabled", enabled);
        obj.addProperty("registered_tick", registeredTick);
        obj.addProperty("last_triggered_tick", lastTriggeredTick);
        obj.addProperty("last_triggered_day", lastTriggeredDay);

        switch (type) {
            case TIME_OF_DAY -> {
                obj.addProperty("time_of_day", timeOfDay);
                obj.addProperty("repeat_days", repeatDays);
            }
            case INTERVAL -> {
                obj.addProperty("interval_ticks", intervalTicks);
                obj.addProperty("repeat", repeat);
            }
            case OBSERVER -> {
                JsonArray obsArr = new JsonArray();
                for (ObserverDef od : observers) {
                    obsArr.add(od.toJson());
                }
                obj.add("observers", obsArr);
                obj.addProperty("threshold", threshold);
            }
        }
        return obj;
    }

    public static ScheduleConfig fromJson(JsonObject obj) {
        ScheduleConfig config = new ScheduleConfig();
        config.type = Type.valueOf(obj.get("type").getAsString());
        config.targetAgent = obj.has("target_agent") ? obj.get("target_agent").getAsString() : "";
        config.promptMessage = obj.has("prompt_message") ? obj.get("prompt_message").getAsString() : "";
        config.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
        config.registeredTick = obj.has("registered_tick") ? obj.get("registered_tick").getAsLong() : 0;
        config.lastTriggeredTick = obj.has("last_triggered_tick") ? obj.get("last_triggered_tick").getAsLong() : -1;
        config.lastTriggeredDay = obj.has("last_triggered_day") ? obj.get("last_triggered_day").getAsLong() : -1;

        switch (config.type) {
            case TIME_OF_DAY -> {
                config.timeOfDay = obj.has("time_of_day") ? obj.get("time_of_day").getAsInt() : 0;
                config.repeatDays = obj.has("repeat_days") ? obj.get("repeat_days").getAsInt() : 1;
            }
            case INTERVAL -> {
                config.intervalTicks = obj.has("interval_ticks") ? obj.get("interval_ticks").getAsInt() : 1200;
                config.repeat = !obj.has("repeat") || obj.get("repeat").getAsBoolean();
            }
            case OBSERVER -> {
                config.observers = new ArrayList<>();
                if (obj.has("observers") && obj.get("observers").isJsonArray()) {
                    for (var el : obj.getAsJsonArray("observers")) {
                        config.observers.add(ObserverDef.fromJson(el.getAsJsonObject()));
                    }
                }
                config.threshold = obj.has("threshold") ? obj.get("threshold").getAsInt() : 1;
            }
        }
        return config;
    }
}
