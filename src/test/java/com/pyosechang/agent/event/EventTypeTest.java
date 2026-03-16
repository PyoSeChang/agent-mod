package com.pyosechang.agent.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventTypeTest {

    @Test
    void enumHasExpectedCount() {
        EventType[] values = EventType.values();
        assertEquals(17, values.length,
                "EventType should have 17 constants (6 runtime + 6 lifecycle + 2 schedule + 3 action)");
    }

    @Test
    void valueOfRoundtripsAllConstants() {
        for (EventType type : EventType.values()) {
            assertSame(type, EventType.valueOf(type.name()),
                    "valueOf(name()) should return same instance for " + type.name());
        }
    }
}
