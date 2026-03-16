package com.pyosechang.agent.event;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {

    @BeforeEach
    void reset() throws Exception {
        var bus = EventBus.getInstance();
        var sf = EventBus.class.getDeclaredField("subscribers");
        sf.setAccessible(true);
        ((java.util.List<?>) sf.get(bus)).clear();
        var hf = EventBus.class.getDeclaredField("history");
        hf.setAccessible(true);
        ((java.util.Deque<?>) hf.get(bus)).clear();
    }

    @Test
    void singletonReturnsSameInstance() {
        assertSame(EventBus.getInstance(), EventBus.getInstance());
    }

    @Test
    void publishDeliversToSubscribedLambda() {
        var bus = EventBus.getInstance();
        var received = new ArrayList<AgentEvent>();
        bus.subscribe(received::add);

        var event = AgentEvent.of("alex", EventType.TOOL_CALL);
        bus.publish(event);

        assertEquals(1, received.size());
        assertSame(event, received.get(0));
    }

    @Test
    void publishDeliversToMultipleSubscribers() {
        var bus = EventBus.getInstance();
        var count = new AtomicInteger(0);
        bus.subscribe(e -> count.incrementAndGet());
        bus.subscribe(e -> count.incrementAndGet());
        bus.subscribe(e -> count.incrementAndGet());

        bus.publish(AgentEvent.of("steve", EventType.SPAWNED));

        assertEquals(3, count.get());
    }

    @Test
    void unsubscribeStopsDelivery() {
        var bus = EventBus.getInstance();
        var count = new AtomicInteger(0);
        EventSubscriber sub = e -> count.incrementAndGet();
        bus.subscribe(sub);

        bus.publish(AgentEvent.of("alex", EventType.SPAWNED));
        assertEquals(1, count.get());

        bus.unsubscribe(sub);
        bus.publish(AgentEvent.of("alex", EventType.DESPAWNED));
        assertEquals(1, count.get(), "Should not receive events after unsubscribe");
    }

    @Test
    void subscriberExceptionDoesNotBreakOthers() {
        var bus = EventBus.getInstance();
        var received = new ArrayList<AgentEvent>();

        bus.subscribe(e -> { throw new RuntimeException("boom"); });
        bus.subscribe(received::add);

        var event = AgentEvent.of("alex", EventType.ERROR);
        bus.publish(event);

        assertEquals(1, received.size(), "Second subscriber should still receive the event");
    }

    @Test
    void getHistoryReturnsEventsInPublishOrder() {
        var bus = EventBus.getInstance();
        var e1 = AgentEvent.of("a", EventType.SPAWNED);
        var e2 = AgentEvent.of("b", EventType.TOOL_CALL);
        var e3 = AgentEvent.of("c", EventType.TEXT);

        bus.publish(e1);
        bus.publish(e2);
        bus.publish(e3);

        List<AgentEvent> history = bus.getHistory();
        assertEquals(3, history.size());
        assertSame(e1, history.get(0));
        assertSame(e2, history.get(1));
        assertSame(e3, history.get(2));
    }

    @Test
    void getHistoryReturnsDefensiveCopy() {
        var bus = EventBus.getInstance();
        bus.publish(AgentEvent.of("alex", EventType.SPAWNED));

        List<AgentEvent> copy = bus.getHistory();
        copy.clear();

        assertEquals(1, bus.getHistory().size(), "Clearing returned list should not affect internal history");
    }

    @Test
    void ringBufferEvictsOldestAt2000() {
        var bus = EventBus.getInstance();
        for (int i = 1; i <= 2001; i++) {
            var data = new JsonObject();
            data.addProperty("seq", i);
            bus.publish(AgentEvent.of("agent", EventType.THOUGHT, data));
        }

        List<AgentEvent> history = bus.getHistory();
        assertEquals(2000, history.size());
        // First event (seq=1) should have been evicted; first remaining is seq=2
        assertEquals(2, history.get(0).data().get("seq").getAsInt());
    }

    @Test
    void publishWithNoSubscribersDoesNotThrow() {
        var bus = EventBus.getInstance();
        assertDoesNotThrow(() -> bus.publish(AgentEvent.of("alex", EventType.SPAWNED)));
    }

    @Test
    void getHistoryEmptyInitially() {
        var bus = EventBus.getInstance();
        assertTrue(bus.getHistory().isEmpty());
    }
}
