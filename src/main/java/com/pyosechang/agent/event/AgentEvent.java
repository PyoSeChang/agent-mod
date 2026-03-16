package com.pyosechang.agent.event;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public record AgentEvent(long timestamp, String agentName, EventType type, JsonObject data) {
    private static final Gson GSON = new Gson();

    public static AgentEvent of(String agentName, EventType type, JsonObject data) {
        return new AgentEvent(System.currentTimeMillis(), agentName, type, data != null ? data : new JsonObject());
    }

    public static AgentEvent of(String agentName, EventType type) {
        return of(agentName, type, null);
    }

    /** SSE wire format: "event: {type}\ndata: {json}\n\n" */
    public String toSSE() {
        JsonObject json = toJson();
        return "event: " + type.name() + "\ndata: " + GSON.toJson(json) + "\n\n";
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("timestamp", timestamp);
        json.addProperty("agentName", agentName);
        json.addProperty("type", type.name());
        json.add("data", data);
        return json;
    }
}
