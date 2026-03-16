package com.pyosechang.agent.event;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SSESubscriberTest {

    @BeforeEach
    void reset() throws Exception {
        var sub = SSESubscriber.getInstance();
        var cf = SSESubscriber.class.getDeclaredField("connections");
        cf.setAccessible(true);
        ((java.util.List<?>) cf.get(sub)).clear();
    }

    @Test
    void getInstance_returnsSingleton() {
        SSESubscriber a = SSESubscriber.getInstance();
        SSESubscriber b = SSESubscriber.getInstance();
        assertSame(a, b);
    }

    @Test
    void onEvent_writesSSEFormattedBytes() {
        SSESubscriber sub = SSESubscriber.getInstance();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        sub.addConnection(baos);

        AgentEvent event = AgentEvent.of("alex", EventType.THOUGHT, new JsonObject());
        sub.onEvent(event);

        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("event:"), "SSE output should contain 'event:'");
        assertTrue(output.contains("data:"), "SSE output should contain 'data:'");
    }

    @Test
    void onEvent_writesToMultipleConnections() {
        SSESubscriber sub = SSESubscriber.getInstance();
        ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        sub.addConnection(baos1);
        sub.addConnection(baos2);

        AgentEvent event = AgentEvent.of("steve", EventType.SPAWNED);
        sub.onEvent(event);

        String out1 = baos1.toString(StandardCharsets.UTF_8);
        String out2 = baos2.toString(StandardCharsets.UTF_8);
        assertTrue(out1.contains("event:"), "First connection should receive SSE data");
        assertTrue(out2.contains("event:"), "Second connection should receive SSE data");
        assertEquals(out1, out2, "Both connections should receive identical data");
    }

    @Test
    void onEvent_brokenConnectionIsRemoved() throws Exception {
        SSESubscriber sub = SSESubscriber.getInstance();

        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken pipe");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("broken pipe");
            }
        };
        sub.addConnection(broken);

        AgentEvent event = AgentEvent.of("alex", EventType.ERROR);
        sub.onEvent(event);

        var cf = SSESubscriber.class.getDeclaredField("connections");
        cf.setAccessible(true);
        var connections = (java.util.List<?>) cf.get(sub);
        assertFalse(connections.contains(broken), "Broken connection should be removed after IOException");
    }

    @Test
    void removeConnection_stopsDelivery() {
        SSESubscriber sub = SSESubscriber.getInstance();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        sub.addConnection(baos);
        sub.removeConnection(baos);

        AgentEvent event = AgentEvent.of("alex", EventType.TEXT);
        sub.onEvent(event);

        assertEquals(0, baos.size(), "Removed connection should not receive any data");
    }

    @Test
    void addConnection_startsHeartbeat() throws Exception {
        SSESubscriber sub = SSESubscriber.getInstance();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        sub.addConnection(baos);

        var hf = SSESubscriber.class.getDeclaredField("heartbeatRunning");
        hf.setAccessible(true);
        Object val = hf.get(sub);
        if (val instanceof java.util.concurrent.atomic.AtomicBoolean ab) {
            assertTrue(ab.get(), "heartbeatRunning should be true after addConnection");
        } else {
            assertTrue((boolean) val, "heartbeatRunning should be true after addConnection");
        }
    }
}
