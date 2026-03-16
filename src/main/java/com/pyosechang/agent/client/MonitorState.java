package com.pyosechang.agent.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds all monitor page state. Created when the screen opens, destroyed on close.
 * Survives page switches (Management ↔ Monitor) within a single screen session.
 */
public class MonitorState {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorState.class);
    private static final int MAX_MESSAGES_PER_AGENT = 500;

    // Per-agent state
    public static class AgentStatus {
        public boolean spawned;
        public boolean runtimeRunning;
        public boolean hasLaunched;
    }

    private final Map<String, List<MonitorMessage>> agentMessages = new ConcurrentHashMap<>();
    private final Map<String, AgentStatus> agentStatuses = new ConcurrentHashMap<>();
    private final List<String> sortedNames = new ArrayList<>();

    private String selectedAgent = "manager";
    private int selectedIndex = 0;
    private boolean verbose = false;

    private SSEClient sseClient;

    // Scroll state for conversation log
    private int scrollOffset = 0;
    private boolean autoScroll = true;

    public MonitorState() {
        ensureAgent("manager");
    }

    // --- Agent management ---

    public void ensureAgent(String name) {
        agentMessages.computeIfAbsent(name, k -> new ArrayList<>());
        agentStatuses.computeIfAbsent(name, k -> new AgentStatus());
        rebuildSortedNames();
    }

    private void rebuildSortedNames() {
        Set<String> names = new TreeSet<>(agentMessages.keySet());
        sortedNames.clear();
        if (names.remove("manager")) sortedNames.add("manager");
        sortedNames.addAll(names);
    }

    // --- SSE lifecycle ---

    public void connect() {
        if (sseClient != null) return;
        sseClient = new SSEClient(event -> {
            Minecraft.getInstance().tell(() -> handleSSEEvent(event));
        });
        sseClient.connect();
    }

    public void disconnect() {
        if (sseClient != null) {
            sseClient.disconnect();
            sseClient = null;
        }
    }

    // --- Data loading ---

    public void loadSessionInfo() {
        BridgeClient.get("/session/info").thenAccept(result -> {
            Minecraft.getInstance().tell(() -> {
                if (result.has("agents") && result.get("agents").isJsonArray()) {
                    for (JsonElement el : result.getAsJsonArray("agents")) {
                        JsonObject a = el.getAsJsonObject();
                        String name = a.get("name").getAsString();
                        ensureAgent(name);
                        AgentStatus status = agentStatuses.get(name);
                        status.spawned = a.has("spawned") && a.get("spawned").getAsBoolean();
                        status.runtimeRunning = a.has("runtimeRunning") && a.get("runtimeRunning").getAsBoolean();
                        status.hasLaunched = a.has("hasLaunched") && a.get("hasLaunched").getAsBoolean();
                    }
                }
            });
        });
    }

    public void loadHistory() {
        BridgeClient.get("/events/history").thenAccept(result -> {
            Minecraft.getInstance().tell(() -> {
                if (result.has("events") && result.get("events").isJsonArray()) {
                    for (JsonElement el : result.getAsJsonArray("events")) {
                        JsonObject ev = el.getAsJsonObject();
                        String type = ev.has("type") ? ev.get("type").getAsString() : "";
                        String agent = ev.has("agentName") ? ev.get("agentName").getAsString() : "manager";
                        if (agent.isEmpty()) agent = "manager";
                        processEvent(type, agent, ev);
                    }
                }
            });
        });
    }

    // --- Event handling ---

    private void handleSSEEvent(SSEClient.SSEEvent event) {
        try {
            JsonObject json = JsonParser.parseString(event.data()).getAsJsonObject();
            String agent = json.has("agentName") ? json.get("agentName").getAsString() : "manager";
            if (agent.isEmpty()) agent = "manager";

            // Update agent status
            switch (event.type()) {
                case "SPAWNED" -> { ensureAgent(agent); agentStatuses.get(agent).spawned = true; }
                case "DESPAWNED" -> { ensureAgent(agent); agentStatuses.get(agent).spawned = false; agentStatuses.get(agent).runtimeRunning = false; }
                case "RUNTIME_STARTED" -> { ensureAgent(agent); agentStatuses.get(agent).runtimeRunning = true; agentStatuses.get(agent).hasLaunched = true; }
                case "RUNTIME_STOPPED" -> { ensureAgent(agent); agentStatuses.get(agent).runtimeRunning = false; }
            }

            processEvent(event.type(), agent, json);
        } catch (Exception e) {
            LOGGER.warn("Failed to process SSE event: {}", e.getMessage());
        }
    }

    private void processEvent(String type, String agent, JsonObject eventJson) {
        ensureAgent(agent);
        List<Component> lines = EventFormatter.formatEvent(type, eventJson);
        MonitorMessage msg = new MonitorMessage(type, agent, System.currentTimeMillis(), lines);

        List<MonitorMessage> messages = agentMessages.get(agent);
        messages.add(msg);
        if (messages.size() > MAX_MESSAGES_PER_AGENT) {
            messages.remove(0);
        }

        // Auto-scroll if viewing this agent and at bottom
        if (agent.equals(selectedAgent) && autoScroll) {
            scrollToBottom();
        }
    }

    public void addUserMessage(String text) {
        List<Component> lines = EventFormatter.formatUserInput(text);
        MonitorMessage msg = new MonitorMessage("USER", selectedAgent, System.currentTimeMillis(), lines);
        agentMessages.computeIfAbsent(selectedAgent, k -> new ArrayList<>()).add(msg);
        if (autoScroll) scrollToBottom();
    }

    // --- Getters ---

    public List<MonitorMessage> getVisibleMessages() {
        List<MonitorMessage> all = agentMessages.getOrDefault(selectedAgent, List.of());
        if (verbose) return all;
        List<MonitorMessage> filtered = new ArrayList<>();
        for (MonitorMessage m : all) {
            if (EventFormatter.isVisibleEvent(m.eventType(), verbose) || "USER".equals(m.eventType())) {
                filtered.add(m);
            }
        }
        return filtered;
    }

    public int getTotalLineCount() {
        int count = 0;
        for (MonitorMessage m : getVisibleMessages()) {
            count += m.lines().size();
        }
        return count;
    }

    public List<String> getSortedNames() { return sortedNames; }
    public String getSelectedAgent() { return selectedAgent; }
    public int getSelectedIndex() { return selectedIndex; }
    public boolean isVerbose() { return verbose; }
    public int getScrollOffset() { return scrollOffset; }
    public AgentStatus getAgentStatus(String name) { return agentStatuses.get(name); }
    public String getSSEStatus() { return sseClient != null ? sseClient.getStatus() : "Not started"; }

    // --- Selection ---

    public void selectAgent(int index) {
        if (index >= 0 && index < sortedNames.size()) {
            selectedIndex = index;
            String name = sortedNames.get(index);
            if (!name.equals(selectedAgent)) {
                selectedAgent = name;
                scrollOffset = 0;
                autoScroll = true;
            }
        }
    }

    public void moveUp() {
        selectAgent(selectedIndex - 1);
    }

    public void moveDown() {
        selectAgent(selectedIndex + 1);
    }

    // --- Verbose ---

    public void toggleVerbose() {
        verbose = !verbose;
        scrollOffset = 0;
        autoScroll = true;
    }

    // --- Scrolling ---

    public void scrollUp(int lines) {
        scrollOffset = Math.min(scrollOffset + lines, Math.max(getTotalLineCount() - 1, 0));
        autoScroll = false;
    }

    public void scrollDown(int lines) {
        scrollOffset = Math.max(scrollOffset - lines, 0);
        if (scrollOffset == 0) autoScroll = true;
    }

    public void scrollToBottom() {
        scrollOffset = 0;
        autoScroll = true;
    }
}
