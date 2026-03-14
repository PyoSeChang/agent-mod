package com.pyosechang.agent.core.memory;

import com.pyosechang.agent.core.schedule.ScheduleConfig;

/**
 * Memory entry for schedules. Config is a first-class object field,
 * not serialized as JSON string in content.
 * Content field is free text (e.g. notes about the schedule).
 */
public class ScheduleMemory extends MemoryEntry {
    private ScheduleConfig config;

    public ScheduleMemory() {
        setCategory("schedule");
    }

    public ScheduleConfig getConfig() { return config; }
    public void setConfig(ScheduleConfig config) { this.config = config; }
}
