package com.pyosechang.agent.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.core.AgentContext;
import com.pyosechang.agent.core.AgentLogger;
import com.pyosechang.agent.core.AgentManager;
import com.pyosechang.agent.core.ObservationBuilder;
import com.pyosechang.agent.core.action.Action;
import com.pyosechang.agent.core.action.ActionRegistry;
import com.pyosechang.agent.core.action.ActiveActionManager;
import com.pyosechang.agent.core.action.AsyncAction;
import com.pyosechang.agent.core.memory.MemoryEntry;
import com.pyosechang.agent.core.memory.MemoryLocation;
import com.pyosechang.agent.core.memory.MemoryManager;
import com.pyosechang.agent.monitor.TerminalIntegration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class AgentHttpServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private HttpServer httpServer;
    private int port;

    public void start() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            port = httpServer.getAddress().getPort();
            httpServer.setExecutor(null);

            // Per-agent endpoints: /agent/{name}/...
            httpServer.createContext("/agent/", this::handleAgentRoute);

            // Global endpoints
            httpServer.createContext("/agents/list", this::handleAgentsFullList);
            httpServer.createContext("/agents/delete", this::handleAgentsDelete);
            httpServer.createContext("/agents", this::handleAgentsList);
            httpServer.createContext("/actions", this::handleActions);
            httpServer.createContext("/log", this::handleLog);

            // Memory endpoints (global scope)
            httpServer.createContext("/memory/create", this::handleMemoryCreate);
            httpServer.createContext("/memory/get", this::handleMemoryGet);
            httpServer.createContext("/memory/update", this::handleMemoryUpdate);
            httpServer.createContext("/memory/delete", this::handleMemoryDelete);
            httpServer.createContext("/memory/search", this::handleMemorySearch);
            httpServer.createContext("/memory/reload", this::handleMemoryReload);

            httpServer.start();
            writePortFile();
            LOGGER.info("Agent HTTP server started on port {}", port);
        } catch (IOException e) {
            LOGGER.error("Failed to start Agent HTTP server", e);
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            LOGGER.info("Agent HTTP server stopped");
        }
        try {
            Path portFile = FMLPaths.GAMEDIR.get().resolve(".agent/bridge-server.json");
            Files.deleteIfExists(portFile);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete port file", e);
        }
    }

    public int getPort() { return port; }

    private void writePortFile() {
        try {
            Path agentDir = FMLPaths.GAMEDIR.get().resolve(".agent");
            Files.createDirectories(agentDir);
            Path portFile = agentDir.resolve("bridge-server.json");
            JsonObject info = new JsonObject();
            info.addProperty("port", port);
            info.addProperty("pid", ProcessHandle.current().pid());
            Files.writeString(portFile, GSON.toJson(info), StandardCharsets.UTF_8);
            LOGGER.info("Wrote port file: {}", portFile);
        } catch (IOException e) {
            LOGGER.error("Failed to write port file", e);
        }
    }

    // ============================================================
    // Per-agent routing: /agent/{name}/{subpath}
    // ============================================================

    private void handleAgentRoute(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // Parse: /agent/{name}/{subpath...}
        // path starts with "/agent/"
        String remainder = path.substring("/agent/".length());
        int slashIdx = remainder.indexOf('/');
        if (slashIdx < 0) {
            sendError(exchange, 400, "Missing sub-path. Use /agent/{name}/{action}");
            return;
        }
        String agentName = remainder.substring(0, slashIdx);
        String subpath = remainder.substring(slashIdx); // includes leading /

        // Persona endpoint works even when agent is not spawned
        if ("/persona".equals(subpath)) {
            handlePersona(exchange, agentName);
            return;
        }

        AgentContext ctx = AgentManager.getInstance().getAgent(agentName);
        if (ctx == null) {
            sendError(exchange, 404, "Agent not found: " + agentName);
            return;
        }

        switch (subpath) {
            case "/observation" -> handleAgentObservation(exchange, ctx);
            case "/action" -> handleAgentAction(exchange, ctx);
            case "/status" -> handleAgentStatus(exchange, ctx);
            case "/intervention" -> handleAgentIntervention(exchange, ctx);
            case "/spawn" -> handleAgentSpawn(exchange);
            case "/despawn" -> handleAgentDespawn(exchange, agentName);
            // Per-agent memory endpoints (auto-scoped to agent)
            case "/memory/create" -> handleAgentMemoryCreate(exchange, agentName);
            case "/memory/get" -> handleMemoryGet(exchange);
            case "/memory/update" -> handleMemoryUpdate(exchange);
            case "/memory/delete" -> handleMemoryDelete(exchange);
            case "/memory/search" -> handleAgentMemorySearch(exchange, agentName);
            default -> sendError(exchange, 404, "Unknown agent endpoint: " + subpath);
        }
    }

    private void handleAgentObservation(HttpExchange exchange, AgentContext ctx) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            MinecraftServer server = AgentManager.getInstance().getServer();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    FakePlayer agent = ctx.getFakePlayer();
                    ServerLevel level = (ServerLevel) agent.level();
                    JsonObject obs = ObservationBuilder.build(agent, level, ctx.getName());
                    com.pyosechang.agent.compat.CompatRegistry.getInstance()
                        .extendObservation(obs, agent);
                    obs.addProperty("ok", true);
                    future.complete(obs);
                } catch (Exception e) {
                    future.complete(errorJson(e.getMessage()));
                }
            });
            sendJson(exchange, 200, future.get(5, TimeUnit.SECONDS));
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleAgentAction(HttpExchange exchange, AgentContext ctx) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        long startMs = System.currentTimeMillis();
        try {
            JsonObject body = readBody(exchange);
            String actionName = body.get("action").getAsString();
            JsonObject params = body;

            ActionRegistry registry = ActionRegistry.getInstance();

            // Async actions get a fresh instance per execution (agent isolation)
            if (registry.isAsync(actionName)) {
                AsyncAction asyncAction = registry.createAsync(actionName);
                if (asyncAction == null) {
                    sendError(exchange, 500, "Failed to create action: " + actionName);
                    return;
                }
                MinecraftServer server = AgentManager.getInstance().getServer();
                CompletableFuture<JsonObject> bridgeFuture = new CompletableFuture<>();
                server.execute(() -> {
                    try {
                        FakePlayer agent = ctx.getFakePlayer();
                        CompletableFuture<JsonObject> actionFuture =
                            ctx.getActionManager().startAction(asyncAction, agent, params);
                        actionFuture.whenComplete((result, ex) -> {
                            if (ex != null) {
                                bridgeFuture.complete(errorJson(ex.getMessage()));
                            } else {
                                bridgeFuture.complete(result);
                            }
                        });
                    } catch (Exception e) {
                        bridgeFuture.complete(errorJson(e.getMessage()));
                    }
                });
                try {
                    long timeoutMs = asyncAction.getTimeoutMs();
                    JsonObject result = bridgeFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
                    AgentLogger.getInstance().logAction(actionName, params, result, System.currentTimeMillis() - startMs);
                    sendJson(exchange, 200, result);
                } catch (java.util.concurrent.TimeoutException te) {
                    ctx.getActionManager().cancel();
                    long timeoutSec = asyncAction.getTimeoutMs() / 1000;
                    JsonObject err = new JsonObject();
                    err.addProperty("ok", false);
                    err.addProperty("error", "Action timed out after " + timeoutSec + " seconds");
                    AgentLogger.getInstance().logAction(actionName, params, err, System.currentTimeMillis() - startMs);
                    sendError(exchange, 504, err.get("error").getAsString());
                }
            } else {
                // Sync action — shared instance (stateless)
                Action action = registry.get(actionName);
                if (action == null) {
                    sendError(exchange, 404, "Unknown action: " + actionName);
                    return;
                }
                MinecraftServer server = AgentManager.getInstance().getServer();
                CompletableFuture<JsonObject> future = new CompletableFuture<>();
                server.execute(() -> {
                    try {
                        FakePlayer agent = ctx.getFakePlayer();
                        JsonObject result = action.execute(agent, params);
                        future.complete(result);
                    } catch (Exception e) {
                        future.complete(errorJson(e.getMessage()));
                    }
                });
                JsonObject result = future.get(5, TimeUnit.SECONDS);
                AgentLogger.getInstance().logAction(actionName, params, result, System.currentTimeMillis() - startMs);
                sendJson(exchange, 200, result);
            }
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleAgentStatus(HttpExchange exchange, AgentContext ctx) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("name", ctx.getName());
            result.addProperty("spawned", true);
            result.addProperty("runtime_running", ctx.isRuntimeRunning());
            FakePlayer agent = ctx.getFakePlayer();
            JsonObject pos = new JsonObject();
            pos.addProperty("x", agent.getX());
            pos.addProperty("y", agent.getY());
            pos.addProperty("z", agent.getZ());
            result.add("position", pos);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleAgentIntervention(HttpExchange exchange, AgentContext ctx) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            JsonObject result = new JsonObject();
            String msg = ctx.getInterventionQueue().poll();
            if (msg != null) {
                result.addProperty("message", msg);
            }
            result.addProperty("ok", true);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleAgentSpawn(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String name = body.get("name").getAsString();
            int x = body.get("x").getAsInt();
            int y = body.get("y").getAsInt();
            int z = body.get("z").getAsInt();

            MinecraftServer server = AgentManager.getInstance().getServer();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    boolean ok = AgentManager.getInstance().spawn(name, level, new BlockPos(x, y, z));
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", ok);
                    if (!ok) result.addProperty("error", "Agent already spawned: " + name);
                    future.complete(result);
                } catch (Exception e) {
                    future.complete(errorJson(e.getMessage()));
                }
            });
            sendJson(exchange, 200, future.get(5, TimeUnit.SECONDS));
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleAgentDespawn(HttpExchange exchange, String agentName) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            MinecraftServer server = AgentManager.getInstance().getServer();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    boolean ok = AgentManager.getInstance().despawn(agentName);
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", ok);
                    if (!ok) result.addProperty("error", "Agent not spawned: " + agentName);
                    future.complete(result);
                } catch (Exception e) {
                    future.complete(errorJson(e.getMessage()));
                }
            });
            sendJson(exchange, 200, future.get(5, TimeUnit.SECONDS));
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ============================================================
    // Global endpoints
    // ============================================================

    private void handleAgentsList(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            JsonArray agents = new JsonArray();
            for (AgentContext ctx : AgentManager.getInstance().getAllAgents()) {
                JsonObject a = new JsonObject();
                a.addProperty("name", ctx.getName());
                a.addProperty("runtime_running", ctx.isRuntimeRunning());
                FakePlayer fp = ctx.getFakePlayer();
                JsonObject pos = new JsonObject();
                pos.addProperty("x", fp.getX());
                pos.addProperty("y", fp.getY());
                pos.addProperty("z", fp.getZ());
                a.add("position", pos);
                agents.add(a);
            }
            result.add("agents", agents);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    /**
     * GET /agents/list — scan .agent/agents/ directory + spawn status for each.
     * Returns both defined (on disk) and spawned agents.
     */
    private void handleAgentsFullList(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            JsonArray agents = new JsonArray();

            // Scan filesystem for defined agents
            Path agentsDir = FMLPaths.GAMEDIR.get().resolve(".agent/agents");
            java.util.Set<String> seen = new java.util.HashSet<>();

            if (Files.isDirectory(agentsDir)) {
                try (var dirs = Files.list(agentsDir)) {
                    dirs.filter(Files::isDirectory).forEach(dir -> {
                        String name = dir.getFileName().toString();
                        seen.add(name);
                        JsonObject a = new JsonObject();
                        a.addProperty("name", name);
                        a.addProperty("defined", true);

                        AgentContext ctx = AgentManager.getInstance().getAgent(name);
                        a.addProperty("spawned", ctx != null);
                        if (ctx != null) {
                            a.addProperty("runtime_running", ctx.isRuntimeRunning());
                            FakePlayer fp = ctx.getFakePlayer();
                            JsonObject pos = new JsonObject();
                            pos.addProperty("x", fp.getX());
                            pos.addProperty("y", fp.getY());
                            pos.addProperty("z", fp.getZ());
                            a.add("position", pos);
                        }

                        // Read persona summary
                        Path personaFile = dir.resolve("PERSONA.md");
                        if (Files.exists(personaFile)) {
                            a.addProperty("has_persona", true);
                            com.pyosechang.agent.core.PersonaConfig pc =
                                com.pyosechang.agent.core.PersonaConfig.parse(name, personaFile);
                            a.addProperty("role", pc.getRole());
                        } else {
                            a.addProperty("has_persona", false);
                        }

                        agents.add(a);
                    });
                }
            }

            // Also include spawned agents that don't have a directory yet
            for (AgentContext ctx : AgentManager.getInstance().getAllAgents()) {
                if (!seen.contains(ctx.getName())) {
                    JsonObject a = new JsonObject();
                    a.addProperty("name", ctx.getName());
                    a.addProperty("defined", false);
                    a.addProperty("spawned", true);
                    a.addProperty("runtime_running", ctx.isRuntimeRunning());
                    a.addProperty("role", ctx.getPersona().getRole());
                    FakePlayer fp = ctx.getFakePlayer();
                    JsonObject pos = new JsonObject();
                    pos.addProperty("x", fp.getX());
                    pos.addProperty("y", fp.getY());
                    pos.addProperty("z", fp.getZ());
                    a.add("position", pos);
                    agents.add(a);
                }
            }

            result.add("agents", agents);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    /**
     * GET/POST /agent/{name}/persona — read or write PERSONA.md
     */
    private void handlePersona(HttpExchange exchange, String agentName) throws IOException {
        Path personaFile = FMLPaths.GAMEDIR.get().resolve(".agent/agents/" + agentName + "/PERSONA.md");

        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("name", agentName);

                com.pyosechang.agent.core.PersonaConfig pc = Files.exists(personaFile)
                    ? com.pyosechang.agent.core.PersonaConfig.parse(agentName, personaFile)
                    : com.pyosechang.agent.core.PersonaConfig.defaultPersona(agentName);

                result.addProperty("role", pc.getRole());
                result.addProperty("personality", pc.getPersonality());
                JsonArray tools = new JsonArray();
                for (String t : pc.getTools()) tools.add(t);
                result.add("tools", tools);
                JsonArray acquaintances = new JsonArray();
                for (var acq : pc.getAcquaintances()) {
                    JsonObject a = new JsonObject();
                    a.addProperty("name", acq.name());
                    a.addProperty("description", acq.description());
                    acquaintances.add(a);
                }
                result.add("acquaintances", acquaintances);
                result.addProperty("raw", pc.getRawContent());

                // Include all available actions for tool selection UI
                JsonArray allActions = new JsonArray();
                for (String name : ActionRegistry.getInstance().listNames()) {
                    allActions.add(name);
                }
                result.add("available_tools", allActions);

                AgentContext ctx = AgentManager.getInstance().getAgent(agentName);
                result.addProperty("spawned", ctx != null);

                sendJson(exchange, 200, result);
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                JsonObject body = readBody(exchange);
                String role = body.has("role") ? body.get("role").getAsString() : "General-purpose agent";
                String personality = body.has("personality") ? body.get("personality").getAsString() : "Helpful and efficient.";
                List<String> tools = new ArrayList<>();
                if (body.has("tools") && body.get("tools").isJsonArray()) {
                    for (JsonElement el : body.getAsJsonArray("tools")) {
                        tools.add(el.getAsString());
                    }
                }

                // Parse acquaintances
                List<String[]> acquaintances = new ArrayList<>();
                if (body.has("acquaintances") && body.get("acquaintances").isJsonArray()) {
                    for (JsonElement el : body.getAsJsonArray("acquaintances")) {
                        JsonObject acq = el.getAsJsonObject();
                        String acqName = acq.get("name").getAsString();
                        String acqDesc = acq.has("description") ? acq.get("description").getAsString() : "";
                        acquaintances.add(new String[]{acqName, acqDesc});
                    }
                }

                // Build PERSONA.md content
                StringBuilder md = new StringBuilder();
                md.append("# ").append(agentName).append("\n\n");
                md.append("## Role\n").append(role).append("\n\n");
                md.append("## Personality\n").append(personality).append("\n\n");
                md.append("## Tools\n");
                for (String t : tools) {
                    md.append("- ").append(t).append("\n");
                }
                if (!acquaintances.isEmpty()) {
                    md.append("\n## Acquaintances\n");
                    for (String[] acq : acquaintances) {
                        md.append("- ").append(acq[0]);
                        if (!acq[1].isEmpty()) md.append(": ").append(acq[1]);
                        md.append("\n");
                    }
                }

                // Ensure directory exists
                Files.createDirectories(personaFile.getParent());
                Files.writeString(personaFile, md.toString(), StandardCharsets.UTF_8);

                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("message", "Persona saved. Despawn and respawn to apply changes.");
                sendJson(exchange, 200, result);
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        } else {
            sendError(exchange, 405, "Method not allowed. Use GET or POST.");
        }
    }

    /**
     * POST /agents/delete — delete agent directory (only if not spawned).
     */
    private void handleAgentsDelete(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String name = body.get("name").getAsString();

            if (AgentManager.getInstance().isSpawned(name)) {
                sendError(exchange, 400, "Cannot delete spawned agent. Despawn first.");
                return;
            }

            Path agentDir = FMLPaths.GAMEDIR.get().resolve(".agent/agents/" + name);
            JsonObject result = new JsonObject();
            if (Files.isDirectory(agentDir)) {
                // Delete directory contents
                try (var walk = Files.walk(agentDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
                }
                result.addProperty("ok", true);
            } else {
                result.addProperty("ok", false);
                result.addProperty("error", "Agent directory not found: " + name);
            }
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleActions(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            JsonArray names = new JsonArray();
            for (String name : ActionRegistry.getInstance().listNames()) {
                names.add(name);
            }
            result.add("actions", names);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleLog(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String type = body.has("type") ? body.get("type").getAsString() : "info";
            String message = body.has("message") ? body.get("message").getAsString() : "";
            TerminalIntegration.sendLog(type, message);
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ============================================================
    // Per-agent memory endpoints (auto-scoped)
    // ============================================================

    /**
     * POST /agent/{name}/memory/create — auto-scoped to agent.
     * If request body doesn't specify scope/visible_to, defaults to agent-scoped.
     */
    private void handleAgentMemoryCreate(HttpExchange exchange, String agentName) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String title = body.get("title").getAsString();
            String description = body.has("description") ? body.get("description").getAsString() : "";
            String content = body.has("content") ? body.get("content").getAsString() : "";
            String category = body.has("category") ? body.get("category").getAsString() : "event";
            List<String> tags = new ArrayList<>();
            if (body.has("tags")) {
                for (JsonElement el : body.getAsJsonArray("tags")) {
                    tags.add(el.getAsString());
                }
            }

            MemoryLocation location = null;
            if (body.has("location") && body.get("location").isJsonObject()) {
                location = MemoryLocation.fromJson(body.getAsJsonObject("location"));
            }

            // Auto-scope: if body specifies scope/visible_to, respect it; otherwise default to agent
            String scope = body.has("scope") ? body.get("scope").getAsString() : "agent:" + agentName;
            List<String> visibleTo = null;
            if (body.has("visible_to") && body.get("visible_to").isJsonArray()) {
                visibleTo = new ArrayList<>();
                for (JsonElement el : body.getAsJsonArray("visible_to")) {
                    visibleTo.add(el.getAsString());
                }
            } else if (!body.has("scope")) {
                // No explicit scope or visible_to — auto-assign to this agent
                visibleTo = List.of(agentName);
            }

            MemoryEntry entry = MemoryManager.getInstance().create(
                title, description, content, category, tags, location, scope, visibleTo);

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.add("entry", MemoryManager.getInstance().entryToJson(entry));
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    /**
     * POST /agent/{name}/memory/search — auto-scoped to agent visibility.
     */
    private void handleAgentMemorySearch(HttpExchange exchange, String agentName) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String query = body.has("query") ? body.get("query").getAsString() : "";
            String category = body.has("category") ? body.get("category").getAsString() : null;
            // Default scope: show entries visible to this agent (global + agent-specific)
            String scope = body.has("scope") ? body.get("scope").getAsString() : "agent:" + agentName;

            List<MemoryEntry> results = MemoryManager.getInstance().search(query, category, scope);

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            JsonArray arr = new JsonArray();
            for (MemoryEntry e : results) {
                arr.add(MemoryManager.getInstance().entryToJson(e));
            }
            result.add("entries", arr);
            result.addProperty("count", results.size());
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ============================================================
    // Memory endpoints (global scope — used by GUI)
    // ============================================================

    private void handleMemoryReload(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            MemoryManager.getInstance().reload();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("count", MemoryManager.getInstance().size());
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleMemoryCreate(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String title = body.get("title").getAsString();
            String description = body.has("description") ? body.get("description").getAsString() : "";
            String content = body.has("content") ? body.get("content").getAsString() : "";
            String category = body.has("category") ? body.get("category").getAsString() : "event";
            List<String> tags = new ArrayList<>();
            if (body.has("tags")) {
                for (JsonElement el : body.getAsJsonArray("tags")) {
                    tags.add(el.getAsString());
                }
            }

            MemoryLocation location = null;
            if (body.has("location") && body.get("location").isJsonObject()) {
                location = MemoryLocation.fromJson(body.getAsJsonObject("location"));
            }

            String scope = body.has("scope") ? body.get("scope").getAsString() : "global";

            List<String> visibleTo = null;
            if (body.has("visible_to") && body.get("visible_to").isJsonArray()) {
                visibleTo = new ArrayList<>();
                for (JsonElement el : body.getAsJsonArray("visible_to")) {
                    visibleTo.add(el.getAsString());
                }
            }

            MemoryEntry entry = MemoryManager.getInstance().create(
                title, description, content, category, tags, location, scope, visibleTo);

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.add("entry", MemoryManager.getInstance().entryToJson(entry));
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleMemoryGet(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String id = body.get("id").getAsString();
            MemoryEntry entry = MemoryManager.getInstance().get(id);
            if (entry == null) {
                sendError(exchange, 404, "Memory not found: " + id);
                return;
            }
            entry.markLoaded();
            MemoryManager.getInstance().save();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.add("entry", MemoryManager.getInstance().entryToJson(entry));
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleMemoryUpdate(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String id = body.get("id").getAsString();
            MemoryEntry entry = MemoryManager.getInstance().update(id, body);
            if (entry == null) {
                sendError(exchange, 404, "Memory not found: " + id);
                return;
            }
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.add("entry", MemoryManager.getInstance().entryToJson(entry));
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleMemoryDelete(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String id = body.get("id").getAsString();
            boolean deleted = MemoryManager.getInstance().delete(id);
            JsonObject result = new JsonObject();
            result.addProperty("ok", deleted);
            if (!deleted) result.addProperty("error", "Memory not found: " + id);
            sendJson(exchange, deleted ? 200 : 404, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleMemorySearch(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String query = body.has("query") ? body.get("query").getAsString() : "";
            String category = body.has("category") ? body.get("category").getAsString() : null;
            String scope = body.has("scope") ? body.get("scope").getAsString() : "global";

            List<MemoryEntry> results = MemoryManager.getInstance().search(query, category, scope);

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            JsonArray arr = new JsonArray();
            for (MemoryEntry e : results) {
                arr.add(MemoryManager.getInstance().entryToJson(e));
            }
            result.add("entries", arr);
            result.addProperty("count", results.size());
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ============================================================
    // Utility methods
    // ============================================================

    private boolean assertMethod(HttpExchange exchange, String method) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method not allowed. Expected " + method);
            return false;
        }
        return true;
    }

    private JsonObject readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private void sendJson(HttpExchange exchange, int code, JsonObject json) throws IOException {
        byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        sendJson(exchange, code, errorJson(message));
    }

    private JsonObject errorJson(String message) {
        JsonObject err = new JsonObject();
        err.addProperty("ok", false);
        err.addProperty("error", message != null ? message : "Unknown error");
        return err;
    }
}
