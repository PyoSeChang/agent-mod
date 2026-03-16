package com.pyosechang.agent.event;

import com.pyosechang.agent.core.AgentLogger;

import java.util.EnumSet;
import java.util.Set;

public class LogSubscriber implements EventSubscriber {
    private static final Set<EventType> HANDLED = EnumSet.of(
        EventType.ACTION_STARTED, EventType.ACTION_COMPLETED, EventType.ACTION_FAILED
    );

    @Override
    public void onEvent(AgentEvent event) {
        if (!HANDLED.contains(event.type())) return;
        AgentLogger logger = AgentLogger.getInstance();
        if (logger != null) {
            logger.logEvent(event.type().name(), event.toJson());
        }
    }
}
