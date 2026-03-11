package com.pyosechang.agent.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.core.FakePlayerManager;
import com.pyosechang.agent.core.ObservationBuilder;
import com.pyosechang.agent.core.action.Action;
import com.pyosechang.agent.core.action.ActionRegistry;
import com.pyosechang.agent.monitor.InterventionQueue;
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

            httpServer.createContext("/spawn", this::handleSpawn);
            httpServer.createContext("/despawn", this::handleDespawn);
            httpServer.createContext("/status", this::handleStatus);
            httpServer.createContext("/observation", this::handleObservation);
            httpServer.createContext("/action", this::handleAction);
            httpServer.createContext("/actions", this::handleActions);
            httpServer.createContext("/log", this::handleLog);
            httpServer.createContext("/intervention", this::handleIntervention);

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
        // Clean up port file
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

    private void handleSpawn(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            int x = body.get("x").getAsInt();
            int y = body.get("y").getAsInt();
            int z = body.get("z").getAsInt();

            MinecraftServer server = FakePlayerManager.getInstance().getServer();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    boolean ok = FakePlayerManager.getInstance().spawn(level, new BlockPos(x, y, z));
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", ok);
                    if (!ok) result.addProperty("error", "Agent already spawned");
                    future.complete(result);
                } catch (Exception e) {
                    JsonObject err = new JsonObject();
                    err.addProperty("ok", false);
                    err.addProperty("error", e.getMessage());
                    future.complete(err);
                }
            });
            JsonObject result = future.get(5, TimeUnit.SECONDS);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleDespawn(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            MinecraftServer server = FakePlayerManager.getInstance().getServer();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    boolean ok = FakePlayerManager.getInstance().despawn();
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", ok);
                    if (!ok) result.addProperty("error", "Agent not spawned");
                    future.complete(result);
                } catch (Exception e) {
                    JsonObject err = new JsonObject();
                    err.addProperty("ok", false);
                    err.addProperty("error", e.getMessage());
                    future.complete(err);
                }
            });
            JsonObject result = future.get(5, TimeUnit.SECONDS);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            MinecraftServer server = FakePlayerManager.getInstance().getServer();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    JsonObject result = new JsonObject();
                    boolean spawned = FakePlayerManager.getInstance().isSpawned();
                    result.addProperty("spawned", spawned);
                    if (spawned) {
                        FakePlayer agent = FakePlayerManager.getInstance().getAgent();
                        JsonObject pos = new JsonObject();
                        pos.addProperty("x", agent.getX());
                        pos.addProperty("y", agent.getY());
                        pos.addProperty("z", agent.getZ());
                        result.add("position", pos);
                    }
                    future.complete(result);
                } catch (Exception e) {
                    JsonObject err = new JsonObject();
                    err.addProperty("ok", false);
                    err.addProperty("error", e.getMessage());
                    future.complete(err);
                }
            });
            JsonObject result = future.get(5, TimeUnit.SECONDS);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleObservation(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            MinecraftServer server = FakePlayerManager.getInstance().getServer();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    FakePlayer agent = FakePlayerManager.getInstance().getAgent();
                    if (agent == null) {
                        JsonObject err = new JsonObject();
                        err.addProperty("ok", false);
                        err.addProperty("error", "Agent not spawned");
                        future.complete(err);
                        return;
                    }
                    ServerLevel level = (ServerLevel) agent.level();
                    JsonObject obs = ObservationBuilder.build(agent, level);
                    // Add mod compat observation data
                    com.pyosechang.agent.compat.CompatRegistry.getInstance()
                        .extendObservation(obs, agent);
                    obs.addProperty("ok", true);
                    future.complete(obs);
                } catch (Exception e) {
                    JsonObject err = new JsonObject();
                    err.addProperty("ok", false);
                    err.addProperty("error", e.getMessage());
                    future.complete(err);
                }
            });
            JsonObject result = future.get(5, TimeUnit.SECONDS);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleAction(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "POST")) return;
        try {
            JsonObject body = readBody(exchange);
            String actionName = body.get("action").getAsString();
            // Pass the whole body as params (MCP tools send flat JSON with action + params)
            JsonObject params = body;

            Action action = ActionRegistry.getInstance().get(actionName);
            if (action == null) {
                sendError(exchange, 404, "Unknown action: " + actionName);
                return;
            }

            MinecraftServer server = FakePlayerManager.getInstance().getServer();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    FakePlayer agent = FakePlayerManager.getInstance().getAgent();
                    if (agent == null) {
                        JsonObject err = new JsonObject();
                        err.addProperty("ok", false);
                        err.addProperty("error", "Agent not spawned");
                        future.complete(err);
                        return;
                    }
                    JsonObject result = action.execute(agent, params);
                    future.complete(result);
                } catch (Exception e) {
                    JsonObject err = new JsonObject();
                    err.addProperty("ok", false);
                    err.addProperty("error", e.getMessage());
                    future.complete(err);
                }
            });
            JsonObject result = future.get(5, TimeUnit.SECONDS);
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

    private void handleIntervention(HttpExchange exchange) throws IOException {
        if (!assertMethod(exchange, "GET")) return;
        try {
            JsonObject result = new JsonObject();
            String msg = InterventionQueue.getInstance().poll();
            if (msg != null) {
                result.addProperty("message", msg);
            }
            result.addProperty("ok", true);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // --- Utility methods ---

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
        JsonObject err = new JsonObject();
        err.addProperty("ok", false);
        err.addProperty("error", message != null ? message : "Unknown error");
        sendJson(exchange, code, err);
    }
}
