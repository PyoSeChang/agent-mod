package com.pyosechang.agent.event;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventTest {

    @Test
    void ofWithDataSetsAllFields() {
        var data = new JsonObject();
        data.addProperty("tool", "mine_block");
        var event = AgentEvent.of("alex", EventType.TOOL_CALL, data);

        assertTrue(event.timestamp() > 0);
        assertEquals("alex", event.agentName());
        assertEquals(EventType.TOOL_CALL, event.type());
        assertEquals("mine_block", event.data().get("tool").getAsString());
    }

    @Test
    void ofWithNullDataReturnsEmptyJsonObject() {
        var event = AgentEvent.of("steve", EventType.SPAWNED, null);

        assertNotNull(event.data());
        assertEquals(0, event.data().size(), "data should be empty JsonObject, not null");
    }

    @Test
    void twoArgOfDelegatesCorrectly() {
        var event = AgentEvent.of("alex", EventType.TEXT);

        assertEquals("alex", event.agentName());
        assertEquals(EventType.TEXT, event.type());
        assertNotNull(event.data());
        assertEquals(0, event.data().size());
        assertTrue(event.timestamp() > 0);
    }

    @Test
    void toJsonHasAllFourFields() {
        var event = AgentEvent.of("alex", EventType.THOUGHT);
        JsonObject json = event.toJson();

        assertTrue(json.has("timestamp"));
        assertTrue(json.has("agentName"));
        assertTrue(json.has("type"));
        assertTrue(json.has("data"));
    }

    @Test
    void toJsonTypeFieldIsEnumNameString() {
        var event = AgentEvent.of("alex", EventType.TOOL_CALL);
        JsonObject json = event.toJson();

        assertEquals("TOOL_CALL", json.get("type").getAsString());
    }

    @Test
    void toSSEFormatStartsWithEventAndEndsWithDoubleNewline() {
        var event = AgentEvent.of("alex", EventType.SPAWNED);
        String sse = event.toSSE();

        assertTrue(sse.startsWith("event: SPAWNED\ndata: "), "SSE should start with event line and data prefix");
        assertTrue(sse.endsWith("\n\n"), "SSE should end with double newline");
    }

    @Test
    void toSSEDataLineIsParseableJsonContainingAgentName() {
        var event = AgentEvent.of("alex", EventType.ACTION_COMPLETED);
        String sse = event.toSSE();

        // Extract the JSON after "data: " and before the trailing "\n\n"
        String dataLine = sse.substring(sse.indexOf("data: ") + 6, sse.length() - 2);
        JsonObject parsed = new Gson().fromJson(dataLine, JsonObject.class);

        assertEquals("alex", parsed.get("agentName").getAsString());
    }

    @Test
    void recordEqualityByValue() {
        long ts = System.currentTimeMillis();
        var data = new JsonObject();
        data.addProperty("key", "value");

        var a = new AgentEvent(ts, "alex", EventType.TOOL_CALL, data);
        var b = new AgentEvent(ts, "alex", EventType.TOOL_CALL, data);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
