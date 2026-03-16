package com.pyosechang.agent.event;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventBus {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EventBus INSTANCE = new EventBus();
    private static final int MAX_HISTORY = 2000;

    private final CopyOnWriteArrayList<EventSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<AgentEvent> history = new ConcurrentLinkedDeque<>();

    private EventBus() {}

    public static EventBus getInstance() { return INSTANCE; }

    public void publish(AgentEvent event) {
        // Add to history (ring buffer)
        history.addLast(event);
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
        }
        // Notify subscribers
        for (EventSubscriber sub : subscribers) {
            try {
                sub.onEvent(event);
            } catch (Exception e) {
                LOGGER.warn("EventBus subscriber failed: {}", e.getMessage());
            }
        }
    }

    public void subscribe(EventSubscriber subscriber) {
        subscribers.add(subscriber);
    }

    public void unsubscribe(EventSubscriber subscriber) {
        subscribers.remove(subscriber);
    }

    public List<AgentEvent> getHistory() {
        return new ArrayList<>(history);
    }
}
