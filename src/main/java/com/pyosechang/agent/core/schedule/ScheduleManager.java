package com.pyosechang.agent.core.schedule;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.core.AgentContext;
import com.pyosechang.agent.core.AgentManager;
import com.pyosechang.agent.core.memory.MemoryEntry;
import com.pyosechang.agent.core.memory.MemoryManager;
import com.pyosechang.agent.core.memory.ScheduleMemory;
import com.pyosechang.agent.runtime.RuntimeManager;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages schedule CRUD and tick-based trigger evaluation.
 * Schedules are ScheduleMemory entries:
 *   - content  = prompt message (sent to agent on trigger)
 *   - description = human-readable summary
 *   - config   = trigger mechanics (type, timing, observers)
 */
public class ScheduleManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ScheduleManager INSTANCE = new ScheduleManager();

    private final ConcurrentHashMap<String, ScheduleMemory> scheduleCache = new ConcurrentHashMap<>();
    private ManagerContext managerContext;

    private ScheduleManager() {}

    public static ScheduleManager getInstance() { return INSTANCE; }

    public void setManagerContext(ManagerContext ctx) { this.managerContext = ctx; }
    public ManagerContext getManagerContext() { return managerContext; }

    public void init() {
        scheduleCache.clear();
        List<MemoryEntry> all = MemoryManager.getInstance().search("", "schedule", "all");
        for (MemoryEntry me : all) {
            if (me instanceof ScheduleMemory sm && sm.getConfig() != null) {
                scheduleCache.put(sm.getId(), sm);
            }
        }
        for (ScheduleMemory sm : scheduleCache.values()) {
            ScheduleConfig config = sm.getConfig();
            config.setLastTriggeredTick(-1);
            config.setLastTriggeredDay(-1);
        }

        LOGGER.info("ScheduleManager initialized with {} schedules", scheduleCache.size());

        for (ScheduleMemory sm : scheduleCache.values()) {
            if (sm.getConfig().getType() == ScheduleConfig.Type.OBSERVER && sm.getConfig().isEnabled()) {
                ObserverManager.getInstance().registerSchedule(sm);
            }
        }
    }

    public void save() {
        MemoryManager.getInstance().save();
    }

    // --- CRUD ---

    /**
     * Create a schedule.
     * @param title display title
     * @param message prompt message sent to agent on trigger (stored in content)
     * @param config trigger configuration
     * @param currentTick server tick at creation time
     */
    public synchronized ScheduleMemory create(String title, String message, ScheduleConfig config, long currentTick) {
        config.setRegisteredTick(currentTick);

        // Build JSON directly to avoid round-trip deserialization issues
        // (Gson reflection can lose parent class fields in modular environments)
        JsonObject json = new JsonObject();
        json.addProperty("category", "schedule");
        json.addProperty("title", title != null ? title : config.getType().name() + " -> " + config.getTargetAgent());
        json.addProperty("content", message);
        json.addProperty("description", buildDescription(config, message));
        json.add("config", config.toJson());

        MemoryEntry created = MemoryManager.getInstance().createFromJson(json);

        if (created instanceof ScheduleMemory createdSm) {
            scheduleCache.put(createdSm.getId(), createdSm);

            if (config.getType() == ScheduleConfig.Type.OBSERVER && config.isEnabled()) {
                ObserverManager.getInstance().registerSchedule(createdSm);
            }

            LOGGER.info("Created schedule '{}' ({}): {} -> {}", createdSm.getId(), config.getType(), config.getTargetAgent(), message);
            return createdSm;
        }

        LOGGER.error("Created memory entry is not ScheduleMemory");
        return null;
    }

    public ScheduleMemory get(String id) {
        return scheduleCache.get(id);
    }

    public synchronized ScheduleMemory update(String id, JsonObject fields) {
        ScheduleMemory sm = scheduleCache.get(id);
        if (sm == null) return null;

        ScheduleConfig config = sm.getConfig();

        // Prompt message → content
        if (fields.has("message")) sm.setContent(fields.get("message").getAsString());
        if (fields.has("target_agent")) config.setTargetAgent(fields.get("target_agent").getAsString());
        if (fields.has("enabled")) config.setEnabled(fields.get("enabled").getAsBoolean());
        if (fields.has("time_of_day")) config.setTimeOfDay(fields.get("time_of_day").getAsInt());
        if (fields.has("repeat_days")) config.setRepeatDays(fields.get("repeat_days").getAsInt());
        if (fields.has("interval_ticks")) config.setIntervalTicks(fields.get("interval_ticks").getAsInt());
        if (fields.has("threshold")) config.setThreshold(fields.get("threshold").getAsInt());

        sm.setDescription(buildDescription(config, sm.getContent()));
        if (fields.has("title")) sm.setTitle(fields.get("title").getAsString());
        sm.markUpdated();

        if (config.getType() == ScheduleConfig.Type.OBSERVER) {
            ObserverManager.getInstance().unregisterSchedule(id);
            if (config.isEnabled()) {
                ObserverManager.getInstance().registerSchedule(sm);
            }
        }

        MemoryManager.getInstance().save();
        LOGGER.info("Updated schedule '{}'", id);
        return sm;
    }

    public synchronized boolean delete(String id) {
        ScheduleMemory sm = scheduleCache.remove(id);
        if (sm == null) return false;

        if (sm.getConfig().getType() == ScheduleConfig.Type.OBSERVER) {
            ObserverManager.getInstance().unregisterSchedule(id);
        }

        boolean deleted = MemoryManager.getInstance().delete(id);
        LOGGER.info("Deleted schedule '{}'", id);
        return deleted;
    }

    public List<ScheduleMemory> list(String targetAgent, boolean enabledOnly) {
        List<ScheduleMemory> result = new ArrayList<>();
        for (ScheduleMemory sm : scheduleCache.values()) {
            if (enabledOnly && !sm.getConfig().isEnabled()) continue;
            if (targetAgent != null && !targetAgent.isEmpty()
                    && !targetAgent.equals(sm.getConfig().getTargetAgent())) continue;
            result.add(sm);
        }
        return result;
    }

    // --- Tick evaluation ---

    public void tick(long dayTime, long dayCount, long serverTick) {
        for (ScheduleMemory sm : scheduleCache.values()) {
            ScheduleConfig config = sm.getConfig();
            if (!config.isEnabled()) continue;

            switch (config.getType()) {
                case TIME_OF_DAY -> checkTimeOfDay(sm, config, dayTime, dayCount);
                case INTERVAL -> checkInterval(sm, config, serverTick);
                case OBSERVER -> {}
            }
        }
    }

    private void checkTimeOfDay(ScheduleMemory sm, ScheduleConfig config, long tickInDay, long dayCount) {
        if (tickInDay != config.getTimeOfDay()) return;
        if (config.getLastTriggeredDay() == dayCount) return;

        if (config.getRepeatDays() == 0 && config.getLastTriggeredDay() >= 0) {
            config.setEnabled(false);
            return;
        }

        if (config.getRepeatDays() > 1 && config.getLastTriggeredDay() >= 0) {
            long daysSince = dayCount - config.getLastTriggeredDay();
            if (daysSince % config.getRepeatDays() != 0) return;
        }

        config.setLastTriggeredDay(dayCount);
        config.setLastTriggeredTick(tickInDay);
        trigger(sm);
    }

    private void checkInterval(ScheduleMemory sm, ScheduleConfig config, long serverTick) {
        long elapsed = serverTick - config.getRegisteredTick();
        if (elapsed <= 0) return;
        if (elapsed % config.getIntervalTicks() != 0) return;

        if (!config.isRepeat() && config.getLastTriggeredTick() >= 0) {
            config.setEnabled(false);
            return;
        }

        config.setLastTriggeredTick(serverTick);
        trigger(sm);
    }

    public void onObserverTriggered(String scheduleId) {
        ScheduleMemory sm = scheduleCache.get(scheduleId);
        if (sm == null) return;
        trigger(sm);
    }

    private void trigger(ScheduleMemory sm) {
        ScheduleConfig config = sm.getConfig();
        String targetAgent = config.getTargetAgent();
        String message = sm.getContent(); // prompt from content field

        LOGGER.info("Schedule '{}' triggered -> {} : {}", sm.getId(), targetAgent, message);

        if (managerContext != null && managerContext.isRuntimeRunning()) {
            JsonObject triggerMsg = new JsonObject();
            triggerMsg.addProperty("type", "schedule_trigger");
            triggerMsg.addProperty("schedule_id", sm.getId());
            triggerMsg.addProperty("schedule_title", sm.getTitle());
            triggerMsg.addProperty("target_agent", targetAgent);
            triggerMsg.addProperty("message", message);
            managerContext.getInterventionQueue().add(triggerMsg.toString());
        } else {
            AgentContext agentCtx = AgentManager.getInstance().getAgent(targetAgent);
            if (agentCtx != null) {
                String scheduleMsg = "[Schedule: " + sm.getTitle() + "] " + message;
                if (agentCtx.isRuntimeRunning()) {
                    agentCtx.getInterventionQueue().add(scheduleMsg);
                    LOGGER.info("Queued intervention to running agent '{}': {}", targetAgent, message);
                } else {
                    net.minecraft.server.MinecraftServer server = AgentManager.getInstance().getServer();
                    if (server != null) {
                        net.minecraft.commands.CommandSourceStack source;
                        var players = server.getPlayerList().getPlayers();
                        if (!players.isEmpty()) {
                            source = players.get(0).createCommandSourceStack();
                        } else {
                            source = server.createCommandSourceStack();
                        }
                        RuntimeManager.getInstance().launch(targetAgent, scheduleMsg, source);
                        LOGGER.info("Launched agent '{}' runtime for schedule trigger: {}", targetAgent, message);
                    }
                }
            } else {
                LOGGER.warn("Schedule '{}' target agent '{}' not spawned, trigger dropped", sm.getId(), targetAgent);
            }
        }

        MemoryManager.getInstance().save();
    }

    /** Build summary JSON for API responses */
    public static JsonObject toSummaryJson(ScheduleMemory sm) {
        ScheduleConfig config = sm.getConfig();
        JsonObject obj = new JsonObject();
        obj.addProperty("id", sm.getId());
        obj.addProperty("title", sm.getTitle());
        obj.addProperty("description", sm.getDescription());
        obj.addProperty("type", config.getType().name());
        obj.addProperty("target_agent", config.getTargetAgent());
        obj.addProperty("prompt_message", sm.getContent()); // from content
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

    static String buildDescription(ScheduleConfig config, String message) {
        String msg = message != null ? message : "";
        return String.format("Schedule: %s -> %s | %s",
            config.getType().name(),
            config.getTargetAgent(),
            msg.length() > 50 ? msg.substring(0, 50) + "..." : msg);
    }

    public int size() { return scheduleCache.size(); }
}
