package com.pyosechang.agent.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Client-side HTTP bridge to AgentHttpServer.
 * Reads port from .agent/bridge-server.json, sends async requests.
 */
public class BridgeClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static int cachedPort = -1;

    private static int getPort() {
        if (cachedPort > 0) return cachedPort;
        try {
            Path portFile = FMLPaths.GAMEDIR.get().resolve(".agent/bridge-server.json");
            if (Files.exists(portFile)) {
                String json = Files.readString(portFile, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                cachedPort = obj.get("port").getAsInt();
                return cachedPort;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read bridge port file", e);
        }
        return -1;
    }

    public static void resetPort() {
        cachedPort = -1;
    }

    public static CompletableFuture<JsonObject> get(String path) {
        int port = getPort();
        if (port < 0) {
            return CompletableFuture.completedFuture(errorJson("Bridge server not available"));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    try {
                        return JsonParser.parseString(resp.body()).getAsJsonObject();
                    } catch (Exception e) {
                        return errorJson("Invalid response: " + resp.body());
                    }
                })
                .exceptionally(e -> errorJson(e.getMessage()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(errorJson(e.getMessage()));
        }
    }

    public static CompletableFuture<JsonObject> post(String path, JsonObject body) {
        int port = getPort();
        if (port < 0) {
            return CompletableFuture.completedFuture(errorJson("Bridge server not available"));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    try {
                        return JsonParser.parseString(resp.body()).getAsJsonObject();
                    } catch (Exception e) {
                        return errorJson("Invalid response: " + resp.body());
                    }
                })
                .exceptionally(e -> errorJson(e.getMessage()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(errorJson(e.getMessage()));
        }
    }

    private static JsonObject errorJson(String msg) {
        JsonObject err = new JsonObject();
        err.addProperty("ok", false);
        err.addProperty("error", msg != null ? msg : "Unknown error");
        return err;
    }
}
