package com.pyosechang.agent.event;

public enum EventType {
    // Runtime (parsed from stdout)
    THOUGHT, TOOL_CALL, TOOL_RESULT, TEXT, ERROR, CHAT,
    // Lifecycle
    SPAWNED, DESPAWNED, RUNTIME_STARTED, RUNTIME_STOPPED, PAUSED, RESUMED,
    // Schedule
    SCHEDULE_TRIGGERED, OBSERVER_FIRED,
    // Action
    ACTION_STARTED, ACTION_COMPLETED, ACTION_FAILED
}
