package com.pyosechang.agent.monitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InterventionQueueTest {

    private InterventionQueue queue;

    @BeforeEach
    void setUp() {
        queue = new InterventionQueue();
    }

    @Test
    void poll_emptyQueue_returnsNull() {
        assertNull(queue.poll());
    }

    @Test
    void add_twoMessages_pollReturnsFifoOrder() {
        queue.add("a");
        queue.add("b");

        assertEquals("a", queue.poll());
        assertEquals("b", queue.poll());
    }

    @Test
    void hasMessages_emptyQueue_returnsFalse() {
        assertFalse(queue.hasMessages());
    }

    @Test
    void hasMessages_afterAdd_returnsTrue() {
        queue.add("hello");
        assertTrue(queue.hasMessages());
    }

    @Test
    void clear_afterAdd_pollReturnsNull() {
        queue.add("x");
        queue.add("y");
        queue.clear();

        assertNull(queue.poll());
        assertFalse(queue.hasMessages());
    }
}
