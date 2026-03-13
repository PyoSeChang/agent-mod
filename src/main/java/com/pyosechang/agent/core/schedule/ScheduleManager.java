package com.pyosechang.agent.core.schedule;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.core.AgentContext;
import com.pyosechang.agent.core.AgentManager;
import com.pyosechang.agent.core.memory.MemoryEntry;
import com.pyosechang.agent.core.memory.MemoryManager;
import com.pyosechang.agent.runtime.RuntimeManager;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages schedule CRUD and tick-based trigger evaluation.
 * Schedules are stored as MemoryEntries with category="schedule".
 */
public class ScheduleManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ScheduleManager INSTANCE = new ScheduleManager();

    // Cached schedule entries for fast tick evaluation
    private final ConcurrentHashMap<String, ScheduleEntry> scheduleCache = new ConcurrentHashMap<>();

    // Manager context for intervention routing
    private ManagerContext managerContext;

    private ScheduleManager() {}

    public static ScheduleManager getInstance() { return INSTANCE; }

    public void setManagerContext(ManagerContext ctx) { this.managerContext = ctx; }
    public ManagerContext getManagerContext() { return managerContext; }

    /** Initialize: load all schedule entries from memory into cache */
    public void init() {
        scheduleCache.clear();
        List<MemoryEntry> all = MemoryManager.getInstance().search("", "schedule", "all");
        for (MemoryEntry me : all) {
            ScheduleEntry se = ScheduleEntry.fromMemoryEntry(me);
            if (se != null) {
                scheduleCache.put(se.getId(), se);
            }
        }
        // Reset tick-based state on server restart (allow re-triggering)
        for (ScheduleEntry se : scheduleCache.values()) {
            ScheduleConfig config = se.getConfig();
            config.setLastTriggeredTick(-1);
            config.setLastTriggeredDay(-1);
            se.updateConfig(config);
        }

        LOGGER.info("ScheduleManager initialized with {} schedules", scheduleCache.size());

        // Register OBSERVER type schedules with ObserverManager
        for (ScheduleEntry se : scheduleCache.values()) {
            if (se.getConfig().getType() == ScheduleConfig.Type.OBSERVER && se.getConfig().isEnabled()) {
                ObserverManager.getInstance().registerSchedule(se);
            }
        }
    }

    /** Save all schedule configs back to memory */
    public void save() {
        for (ScheduleEntry se : scheduleCache.values()) {
            se.updateConfig(se.getConfig()); // persist content to MemoryEntry
        }
        MemoryManager.getInstance().save();
    }

    // --- CRUD ---

    public synchronized ScheduleEntry create(String title, ScheduleConfig config, long currentTick) {
        config.setRegisteredTick(currentTick);
        MemoryEntry me = MemoryManager.getInstance().create(
            title != null ? title : config.getType().name() + " → " + config.getTargetAgent(),
            ScheduleEntry.buildDescription(config),
            config.toJson().toString(),
            ScheduleEntry.CATEGORY,
            List.of("schedule", config.getType().name().toLowerCase()),
            null, "global", null
        );
        ScheduleEntry se = new ScheduleEntry(me);
        scheduleCache.put(se.getId(), se);

        if (config.getType() == ScheduleConfig.Type.OBSERVER && config.isEnabled()) {
            ObserverManager.getInstance().registerSchedule(se);
        }

        LOGGER.info("Created schedule '{}' ({}): {} → {}", se.getId(), config.getType(), config.getTargetAgent(), config.getPromptMessage());
        return se;
    }

    public ScheduleEntry get(String id) {
        return scheduleCache.get(id);
    }

    public synchronized ScheduleEntry update(String id, JsonObject fields) {
        ScheduleEntry se = scheduleCache.get(id);
        if (se == null) return null;

        ScheduleConfig config = se.getConfig();

        if (fields.has("message")) config.setPromptMessage(fields.get("message").getAsString());
        if (fields.has("target_agent")) config.setTargetAgent(fields.get("target_agent").getAsString());
        if (fields.has("enabled")) config.setEnabled(fields.get("enabled").getAsBoolean());
        if (fields.has("time_of_day")) config.setTimeOfDay(fields.get("time_of_day").getAsInt());
        if (fields.has("repeat_days")) config.setRepeatDays(fields.get("repeat_days").getAsInt());
        if (fields.has("interval_ticks")) config.setIntervalTicks(fields.get("interval_ticks").getAsInt());
        if (fields.has("threshold")) config.setThreshold(fields.get("threshold").getAsInt());

        se.updateConfig(config);

        // Update backing MemoryEntry
        JsonObject memFields = new JsonObject();
        memFields.addProperty("content", config.toJson().toString());
        memFields.addProperty("description", ScheduleEntry.buildDescription(config));
        if (fields.has("title")) memFields.addProperty("title", fields.get("title").getAsString());
        MemoryManager.getInstance().update(id, memFields);

        // Re-register observers if needed
        if (config.getType() == ScheduleConfig.Type.OBSERVER) {
            ObserverManager.getInstance().unregisterSchedule(id);
            if (config.isEnabled()) {
                ObserverManager.getInstance().registerSchedule(se);
            }
        }

        LOGGER.info("Updated schedule '{}'", id);
        return se;
    }

    public synchronized boolean delete(String id) {
        ScheduleEntry se = scheduleCache.remove(id);
        if (se == null) return false;

        if (se.getConfig().getType() == ScheduleConfig.Type.OBSERVER) {
            ObserverManager.getInstance().unregisterSchedule(id);
        }

        boolean deleted = MemoryManager.getInstance().delete(id);
        LOGGER.info("Deleted schedule '{}'", id);
        return deleted;
    }

    public List<ScheduleEntry> list(String targetAgent, boolean enabledOnly) {
        List<ScheduleEntry> result = new ArrayList<>();
        for (ScheduleEntry se : scheduleCache.values()) {
            if (enabledOnly && !se.getConfig().isEnabled()) continue;
            if (targetAgent != null && !targetAgent.isEmpty()
                    && !targetAgent.equals(se.getConfig().getTargetAgent())) continue;
            result.add(se);
        }
        return result;
    }

    // --- Tick evaluation ---

    /** Called every server tick to check schedule triggers */
    public void tick(long dayTime, long dayCount, long serverTick) {
        for (ScheduleEntry se : scheduleCache.values()) {
            ScheduleConfig config = se.getConfig();
            if (!config.isEnabled()) continue;

            switch (config.getType()) {
                case TIME_OF_DAY -> checkTimeOfDay(se, config, dayTime, dayCount);
                case INTERVAL -> checkInterval(se, config, serverTick);
                case OBSERVER -> {} // handled by ObserverManager events
            }
        }
    }

    private void checkTimeOfDay(ScheduleEntry se, ScheduleConfig config, long tickInDay, long dayCount) {
        if (tickInDay != config.getTimeOfDay()) return;

        // Prevent duplicate triggers on the same day
        if (config.getLastTriggeredDay() == dayCount) return;

        // Check repeat_days (0=once, already triggered if lastTriggeredDay >= 0)
        if (config.getRepeatDays() == 0 && config.getLastTriggeredDay() >= 0) {
            config.setEnabled(false);
            se.updateConfig(config);
            return;
        }

        // Check day interval
        if (config.getRepeatDays() > 1 && config.getLastTriggeredDay() >= 0) {
            long daysSince = dayCount - config.getLastTriggeredDay();
            if (daysSince % config.getRepeatDays() != 0) return;
        }

        config.setLastTriggeredDay(dayCount);
        config.setLastTriggeredTick(tickInDay);
        se.updateConfig(config);
        trigger(se);
    }

    private void checkInterval(ScheduleEntry se, ScheduleConfig config, long serverTick) {
        long elapsed = serverTick - config.getRegisteredTick();
        if (elapsed <= 0) return;
        if (elapsed % config.getIntervalTicks() != 0) return;

        // If not repeating and already triggered once
        if (!config.isRepeat() && config.getLastTriggeredTick() >= 0) {
            config.setEnabled(false);
            se.updateConfig(config);
            return;
        }

        config.setLastTriggeredTick(serverTick);
        se.updateConfig(config);
        trigger(se);
    }

    /** Called by ObserverManager when threshold is reached */
    public void onObserverTriggered(String scheduleId) {
        ScheduleEntry se = scheduleCache.get(scheduleId);
        if (se == null) return;
        trigger(se);
    }

    private void trigger(ScheduleEntry se) {
        ScheduleConfig config = se.getConfig();
        String targetAgent = config.getTargetAgent();
        String message = config.getPromptMessage();

        LOGGER.info("Schedule '{}' triggered → {} : {}", se.getId(), targetAgent, message);

        // Route through manager if running, otherwise direct fallback
        if (managerContext != null && managerContext.isRuntimeRunning()) {
            // Queue trigger notification for manager runtime
            JsonObject triggerMsg = new JsonObject();
            triggerMsg.addProperty("type", "schedule_trigger");
            triggerMsg.addProperty("schedule_id", se.getId());
            triggerMsg.addProperty("schedule_title", se.getMemoryEntry().getTitle());
            triggerMsg.addProperty("target_agent", targetAgent);
            triggerMsg.addProperty("message", message);
            managerContext.getInterventionQueue().add(triggerMsg.toString());
        } else {
            // Fallback: launch agent runtime directly with the message
            AgentContext agentCtx = AgentManager.getInstance().getAgent(targetAgent);
            if (agentCtx != null) {
                String scheduleMsg = "[Schedule: " + se.getMemoryEntry().getTitle() + "] " + message;
                if (agentCtx.isRuntimeRunning()) {
                    agentCtx.getInterventionQueue().add(scheduleMsg);
                    LOGGER.info("Queued intervention to running agent '{}': {}", targetAgent, message);
                } else {
                    // Launch runtime with the schedule message (send output to online players)
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
                LOGGER.warn("Schedule '{}' target agent '{}' not spawned, trigger dropped", se.getId(), targetAgent);
            }
        }

        // Save updated trigger timestamps
        MemoryManager.getInstance().save();
    }

    /** Get schedule count */
    public int size() { return scheduleCache.size(); }
}
