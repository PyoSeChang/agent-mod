package com.pyosechang.agent.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.core.AgentConfig;
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
import com.pyosechang.agent.core.schedule.*;
import com.pyosechang.agent.event.AgentEvent;
import com.pyosechang.agent.event.EventBus;
import com.pyosechang.agent.event.SSESubscriber;
import com.pyosechang.agent.runtime.ManagerRuntimeManager;
import com.pyosechang.agent.runtime.RuntimeManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import java.util.concurrent.Executors;
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
            httpServer.setExecutor(Executors.newCachedThreadPool());

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

            // Schedule endpoints
            httpServer.createContext("/schedule/create", this::handleScheduleCreate);
            httpServer.createContext("/schedule/update", this::handleScheduleUpdate);
            httpServer.createContext("/schedule/delete", this::handleScheduleDelete);
            httpServer.createContext("/schedule/list", this::handleScheduleList);
            httpServer.createContext("/schedule/get", this::handleScheduleGet);

            // Observer endpoints
            httpServer.createContext("/observer/add", this::handleObserverAdd);
            httpServer.createContext("/observer/remove", this::handleObserverRemove);
            httpServer.createContext("/observer/list", this::handleObserverList);

            // Manager endpoints
            httpServer.createContext("/manager/intervention", this::handleManagerIntervention);
            httpServer.createContext("/manager/world_time", this::handleManagerWorldTime);
            httpServer.createContext("/manager/events", this::handleManagerEvents);
            httpServer.createContext("/manager/tell", this::handleManagerTell);

            // Event/TUI endpoints
            httpServer.createContext("/events/stream", this::handleSSEStream);
            httpServer.createContext("/events/history", this::handleEventsHistory);
            httpServer.createContext("/session/info", this::handleSessionInfo);

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

        // Config endpoint works even when agent is not spawned
        if ("/config".equals(subpath)) {
            handleConfig(exchange, agentName);
            return;
        }

        // Tell endpoint: sends message + launches runtime (works even if not spawned yet via spawn first)
        if ("/tell".equals(subpath)) {
            handleAgentTell(exchange, agentName);
            return;
        }

        // Stop endpoint: stops agent runtime
        if ("/stop".equals(subpath)) {
            handleAgentStop(exchange, agentName);
            return;
        }

        // Spawn endpoint: must work before agent exists
        if ("/spawn".equals(subpath)) {
            handleAgentSpawn(exchange);
            return;
        }

        // Despawn endpoint: works without ctx (despawn by name)
        if ("/despawn".equals(subpath)) {
            handleAgentDespawn(exchange, agentName);
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
            // /spawn and /despawn handled above (before ctx null check)
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
                    ServerPlayer agent = ctx.getPlayer();
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
                        ServerPlayer agent = ctx.getPlayer();
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
                        ServerPlayer agent = ctx.getPlayer();
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
            ServerPlayer agent = ctx.getPlayer();
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

    private void handleAgentStop(HttpExchange exchange, String agentName) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            RuntimeManager.getInstance().stop(agentName);
            JsonObject result = new JsonObject();
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
            int x = body.has("x") ? body.get("x").getAsInt() : 0;
            int y = body.has("y") ? body.get("y").getAsInt() : 64;
            int z = body.has("z") ? body.get("z").getAsInt() : 0;

            MinecraftServer server = AgentManager.getInstance().getServer();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            final int fx = x, fy = y, fz = z;
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    // Default coords (0,64,0) → spawn near first online player
                    BlockPos spawnPos;
                    if (fx == 0 && fz == 0 && !server.getPlayerList().getPlayers().isEmpty()) {
                        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                        spawnPos = player.blockPosition().offset(2, 0, 0);
                    } else {
                        spawnPos = new BlockPos(fx, fy, fz);
                    }
                    boolean ok = AgentManager.getInstance().spawn(name, level, spawnPos);
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
                ServerPlayer fp = ctx.getPlayer();
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
                            ServerPlayer fp = ctx.getPlayer();
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

                        // Read config summary
                        AgentConfig cfg = AgentConfig.load(name);
                        a.addProperty("gamemode", cfg.getGamemode().name());
                        a.addProperty("has_bed", cfg.hasBed());

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
                    ServerPlayer fp = ctx.getPlayer();
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
     * GET/POST /agent/{name}/config — read or write agent config (gamemode, bed).
     */
    private void handleConfig(HttpExchange exchange, String agentName) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                AgentConfig config = AgentConfig.load(agentName);
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("name", agentName);
                result.add("config", config.toJson());
                sendJson(exchange, 200, result);
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                JsonObject body = readBody(exchange);
                AgentConfig config = AgentConfig.load(agentName);

                // Gamemode update
                if (body.has("gamemode")) {
                    String gm = body.get("gamemode").getAsString();
                    AgentConfig.Gamemode newMode;
                    try {
                        newMode = AgentConfig.Gamemode.valueOf(gm);
                    } catch (IllegalArgumentException e) {
                        sendError(exchange, 400, "Invalid gamemode: " + gm);
                        return;
                    }

                    // CREATIVE restriction: check if requesting player is creative
                    if (newMode == AgentConfig.Gamemode.CREATIVE && body.has("player")) {
                        String playerName = body.get("player").getAsString();
                        MinecraftServer server = AgentManager.getInstance().getServer();
                        if (server != null) {
                            ServerPlayer requestingPlayer = server.getPlayerList().getPlayerByName(playerName);
                            if (requestingPlayer != null && !requestingPlayer.isCreative()) {
                                sendError(exchange, 403, "Creative mode requires the player to be in creative mode");
                                return;
                            }
                        }
                    }
                    config.setGamemode(newMode);
                }

                // Bed update
                if (body.has("bed")) {
                    if (body.get("bed").isJsonNull()) {
                        config.clearBed();
                    } else {
                        JsonObject bed = body.getAsJsonObject("bed");
                        config.setBed(
                            bed.get("x").getAsInt(),
                            bed.get("y").getAsInt(),
                            bed.get("z").getAsInt(),
                            bed.has("dimension") ? bed.get("dimension").getAsString() : "minecraft:overworld"
                        );
                    }
                }

                config.save(agentName);

                // Update in-memory config if agent is spawned
                AgentContext ctx = AgentManager.getInstance().getAgent(agentName);
                if (ctx != null) {
                    ctx.setConfig(config);
                }

                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("message", "Config saved. Despawn and respawn to apply gamemode changes.");
                result.add("config", config.toJson());
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

            // Auto-scope: if body doesn't specify visibleTo, default to this agent
            if (!body.has("visibleTo") && !body.has("visible_to")) {
                JsonArray vt = new JsonArray();
                vt.add(agentName);
                body.add("visibleTo", vt);
            }

            // Delegate to createFromJson — handles category dispatch, location deserialization
            MemoryEntry entry = MemoryManager.getInstance().createFromJson(body);

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

            // Normalize visible_to → visibleTo for Gson
            if (body.has("visible_to") && !body.has("visibleTo")) {
                body.add("visibleTo", body.get("visible_to"));
            }

            MemoryEntry entry = MemoryManager.getInstance().createFromJson(body);

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
    // Agent Tell endpoint
    // ============================================================

    private void handleAgentTell(HttpExchange exchange, String agentName) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String message = body.get("message").getAsString();

            AgentContext ctx = AgentManager.getInstance().getAgent(agentName);
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);

            if (ctx != null) {
                if (ctx.isRuntimeRunning()) {
                    ctx.getInterventionQueue().add(message);
                    result.addProperty("launched", false);
                } else {
                    // Need a CommandSourceStack — use server console
                    MinecraftServer server = AgentManager.getInstance().getServer();
                    if (server != null) {
                        RuntimeManager.getInstance().launch(agentName, message, server.createCommandSourceStack());
                        result.addProperty("launched", true);
                    } else {
                        ctx.getInterventionQueue().add(message);
                        result.addProperty("launched", false);
                    }
                }
            } else {
                result.addProperty("ok", false);
                result.addProperty("error", "Agent not spawned: " + agentName);
            }
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ============================================================
    // Schedule endpoints
    // ============================================================

    private void handleScheduleCreate(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String typeStr = body.get("type").getAsString();
            ScheduleConfig.Type type = ScheduleConfig.Type.valueOf(typeStr);

            String targetAgent = body.get("target_agent").getAsString();
            String message = body.get("message").getAsString();
            String title = body.has("title") ? body.get("title").getAsString() : null;
            boolean enabled = !body.has("enabled") || body.get("enabled").getAsBoolean();

            // Validate target agent exists on disk
            Path agentDir = FMLPaths.GAMEDIR.get().resolve(".agent/agents/" + targetAgent);
            if (!Files.isDirectory(agentDir)) {
                sendError(exchange, 400, "Target agent directory not found: " + targetAgent);
                return;
            }

            ScheduleConfig config = new ScheduleConfig();
            config.setType(type);
            config.setTargetAgent(targetAgent);
            config.setEnabled(enabled);

            switch (type) {
                case TIME_OF_DAY -> {
                    if (!body.has("time_of_day")) {
                        sendError(exchange, 400, "time_of_day required for TIME_OF_DAY type");
                        return;
                    }
                    config.setTimeOfDay(body.get("time_of_day").getAsInt());
                    config.setRepeatDays(body.has("repeat_days") ? body.get("repeat_days").getAsInt() : 1);
                }
                case INTERVAL -> {
                    if (!body.has("interval_ticks")) {
                        sendError(exchange, 400, "interval_ticks required for INTERVAL type");
                        return;
                    }
                    config.setIntervalTicks(body.get("interval_ticks").getAsInt());
                    config.setRepeat(!body.has("repeat") || body.get("repeat").getAsBoolean());
                }
                case OBSERVER -> {
                    if (!body.has("observers") || !body.has("threshold")) {
                        sendError(exchange, 400, "observers and threshold required for OBSERVER type");
                        return;
                    }
                    List<ObserverDef> observers = new ArrayList<>();
                    for (var el : body.getAsJsonArray("observers")) {
                        observers.add(ObserverDef.fromJson(el.getAsJsonObject()));
                    }
                    config.setObservers(observers);
                    config.setThreshold(body.get("threshold").getAsInt());
                }
            }

            MinecraftServer server = AgentManager.getInstance().getServer();
            long currentTick = server != null ? server.getTickCount() : 0;

            com.pyosechang.agent.core.memory.ScheduleMemory sm = ScheduleManager.getInstance().create(title, message, config, currentTick);
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.add("entry", ScheduleManager.toSummaryJson(sm));
            sendJson(exchange, 200, result);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Invalid schedule type: " + e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleScheduleUpdate(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String id = body.get("id").getAsString();
            com.pyosechang.agent.core.memory.ScheduleMemory sm = ScheduleManager.getInstance().update(id, body);
            if (sm == null) {
                sendError(exchange, 404, "Schedule not found: " + id);
                return;
            }
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.add("entry", ScheduleManager.toSummaryJson(sm));
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleScheduleDelete(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String id = body.get("id").getAsString();
            boolean deleted = ScheduleManager.getInstance().delete(id);
            JsonObject result = new JsonObject();
            result.addProperty("ok", deleted);
            if (!deleted) result.addProperty("error", "Schedule not found: " + id);
            sendJson(exchange, deleted ? 200 : 404, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleScheduleList(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String targetAgent = body.has("target_agent") ? body.get("target_agent").getAsString() : null;
            boolean enabledOnly = body.has("enabled_only") && body.get("enabled_only").getAsBoolean();

            List<com.pyosechang.agent.core.memory.ScheduleMemory> schedules = ScheduleManager.getInstance().list(targetAgent, enabledOnly);
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            JsonArray arr = new JsonArray();
            for (com.pyosechang.agent.core.memory.ScheduleMemory sm : schedules) {
                arr.add(ScheduleManager.toSummaryJson(sm));
            }
            result.add("schedules", arr);
            result.addProperty("count", schedules.size());
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleScheduleGet(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String id = body.get("id").getAsString();
            com.pyosechang.agent.core.memory.ScheduleMemory sm = ScheduleManager.getInstance().get(id);
            if (sm == null) {
                sendError(exchange, 404, "Schedule not found: " + id);
                return;
            }
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            JsonObject schedule = ScheduleManager.toSummaryJson(sm);
            // Add observer states if OBSERVER type
            if (sm.getConfig().getType() == ScheduleConfig.Type.OBSERVER) {
                JsonObject states = ObserverManager.getInstance().getStates(id);
                schedule.addProperty("observers_triggered", states.get("triggered_count").getAsInt());
                schedule.addProperty("observers_total", sm.getConfig().getObservers().size());
            }
            result.add("schedule", schedule);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ============================================================
    // Observer endpoints
    // ============================================================

    private void handleObserverAdd(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String scheduleId = body.get("schedule_id").getAsString();
            com.pyosechang.agent.core.memory.ScheduleMemory sm = ScheduleManager.getInstance().get(scheduleId);
            if (sm == null || sm.getConfig().getType() != ScheduleConfig.Type.OBSERVER) {
                sendError(exchange, 404, "OBSERVER schedule not found: " + scheduleId);
                return;
            }

            List<ObserverDef> newObservers = new ArrayList<>();
            for (var el : body.getAsJsonArray("observers")) {
                newObservers.add(ObserverDef.fromJson(el.getAsJsonObject()));
            }

            // Add to config
            List<ObserverDef> all = new ArrayList<>(sm.getConfig().getObservers());
            all.addAll(newObservers);
            sm.getConfig().setObservers(all);

            // Register with ObserverManager
            ObserverManager.getInstance().addObservers(scheduleId, newObservers);

            MemoryManager.getInstance().save();

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("total_observers", all.size());
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleObserverRemove(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String scheduleId = body.get("schedule_id").getAsString();
            com.pyosechang.agent.core.memory.ScheduleMemory sm = ScheduleManager.getInstance().get(scheduleId);
            if (sm == null || sm.getConfig().getType() != ScheduleConfig.Type.OBSERVER) {
                sendError(exchange, 404, "OBSERVER schedule not found: " + scheduleId);
                return;
            }

            List<BlockPos> positions = new ArrayList<>();
            for (var el : body.getAsJsonArray("positions")) {
                JsonObject pos = el.getAsJsonObject();
                positions.add(new BlockPos(pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt()));
            }

            // Remove from config
            java.util.Set<BlockPos> posSet = new java.util.HashSet<>(positions);
            List<ObserverDef> remaining = sm.getConfig().getObservers().stream()
                .filter(def -> !posSet.contains(def.getBlockPos()))
                .collect(java.util.stream.Collectors.toList());
            sm.getConfig().setObservers(remaining);

            // Unregister from ObserverManager
            ObserverManager.getInstance().removeObservers(scheduleId, positions);

            MemoryManager.getInstance().save();

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("remaining_observers", remaining.size());
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleObserverList(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String scheduleId = body.get("schedule_id").getAsString();

            JsonObject states = ObserverManager.getInstance().getStates(scheduleId);
            states.addProperty("ok", true);
            sendJson(exchange, 200, states);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ============================================================
    // Manager endpoints
    // ============================================================

    private void handleManagerIntervention(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            ManagerContext ctx = ScheduleManager.getInstance().getManagerContext();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            if (ctx != null) {
                String msg = ctx.getInterventionQueue().poll();
                if (msg != null) {
                    result.addProperty("message", msg);
                }
            }
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleManagerWorldTime(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            MinecraftServer server = AgentManager.getInstance().getServer();
            if (server == null) {
                sendError(exchange, 500, "Server not available");
                return;
            }
            ServerLevel overworld = server.overworld();
            long dayTime = overworld.getDayTime();
            long tickInDay = dayTime % 24000;
            long dayCount = dayTime / 24000;

            String phase;
            if (tickInDay < 6000) phase = "morning";
            else if (tickInDay < 12000) phase = "noon";
            else if (tickInDay < 13000) phase = "sunset";
            else if (tickInDay < 18000) phase = "night";
            else phase = "midnight";

            // Convert to 12h time format
            int hours = (int) ((tickInDay / 1000 + 6) % 24);
            int minutes = (int) ((tickInDay % 1000) * 60 / 1000);
            String ampm = hours >= 12 ? "PM" : "AM";
            int h12 = hours % 12;
            if (h12 == 0) h12 = 12;

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("day_time", dayTime);
            result.addProperty("time_of_day", tickInDay);
            result.addProperty("day_count", dayCount);
            result.addProperty("phase", phase);
            result.addProperty("real_time_desc", String.format("Day %d, %d:%02d %s", dayCount, h12, minutes, ampm));
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleManagerEvents(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            JsonArray events = new JsonArray();
            for (var entry : ObserverManager.SUPPORTED_EVENTS.entrySet()) {
                JsonObject ev = new JsonObject();
                ev.addProperty("type", entry.getKey());
                ev.addProperty("description", entry.getValue());
                events.add(ev);
            }
            result.add("events", events);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ============================================================
    // Event/TUI endpoints
    // ============================================================

    /** GET /events/stream — SSE event stream */
    private void handleSSEStream(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        SSESubscriber.getInstance().addConnection(os);
        try {
            // Block until connection breaks (detected by failed write)
            while (true) {
                Thread.sleep(1000);
                try {
                    os.write(":\n\n".getBytes());  // SSE comment as keepalive probe
                    os.flush();
                } catch (IOException e) {
                    break;  // Client disconnected
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            SSESubscriber.getInstance().removeConnection(os);
            exchange.close();
        }
    }

    /** GET /events/history — event history as JSON array */
    private void handleEventsHistory(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            JsonArray arr = new JsonArray();
            for (AgentEvent event : EventBus.getInstance().getHistory()) {
                arr.add(event.toJson());
            }
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.add("events", arr);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    /** GET /session/info — agent list + manager state */
    private void handleSessionInfo(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);

            // Manager state
            ManagerContext mCtx = ScheduleManager.getInstance().getManagerContext();
            JsonObject manager = new JsonObject();
            manager.addProperty("runtimeRunning", mCtx != null && mCtx.isRuntimeRunning());
            result.add("manager", manager);

            // Agent list (spawned + disk)
            JsonArray agents = new JsonArray();
            java.util.Set<String> seen = new java.util.HashSet<>();

            for (AgentContext ctx : AgentManager.getInstance().getAllAgents()) {
                JsonObject agent = new JsonObject();
                agent.addProperty("name", ctx.getName());
                agent.addProperty("spawned", true);
                agent.addProperty("hasLaunched", ctx.hasLaunched());
                agent.addProperty("runtimeRunning", ctx.isRuntimeRunning());
                agent.addProperty("sessionId", ctx.getSessionId());
                agents.add(agent);
                seen.add(ctx.getName());
            }

            // Disk agents (not spawned)
            Path agentsDir = FMLPaths.GAMEDIR.get().resolve(".agent/agents");
            if (Files.isDirectory(agentsDir)) {
                try (var dirs = Files.list(agentsDir)) {
                    dirs.filter(Files::isDirectory).forEach(dir -> {
                        String name = dir.getFileName().toString();
                        if (!seen.contains(name)) {
                            JsonObject agent = new JsonObject();
                            agent.addProperty("name", name);
                            agent.addProperty("spawned", false);
                            agent.addProperty("hasLaunched", false);
                            agent.addProperty("runtimeRunning", false);
                            agents.add(agent);
                        }
                    });
                }
            }

            result.add("agents", agents);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    /** POST /manager/tell — send message to Agent Manager */
    private void handleManagerTell(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String message = body.get("message").getAsString();

            MinecraftServer server = AgentManager.getInstance().getServer();
            if (server == null) {
                sendError(exchange, 500, "Server not available");
                return;
            }

            ManagerRuntimeManager.getInstance().launchOrMessage(message, server.createCommandSourceStack());

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
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
