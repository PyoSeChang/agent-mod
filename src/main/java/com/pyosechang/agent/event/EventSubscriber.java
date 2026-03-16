package com.pyosechang.agent.event;

@FunctionalInterface
public interface EventSubscriber {
    void onEvent(AgentEvent event);
}
