package com.pyosechang.agent.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Structured JSON-lines logger for agent actions.
 * Each session gets a persistent file: .agent/logs/YYYY-MM-DD_HHmmss.jsonl
 * Every action execution logs: action, params, result, duration, sub-step details.
 */
public class AgentLogger {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    private static final AgentLogger INSTANCE = new AgentLogger();

    private BufferedWriter writer;
    private Path logFile;
    private long sessionStartMs;

    public static AgentLogger getInstance() { return INSTANCE; }

    /**
     * Start a new session log file.
     */
    public void startSession() {
        try {
            Path logsDir = FMLPaths.GAMEDIR.get().resolve(".agent/logs");
            Files.createDirectories(logsDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
            logFile = logsDir.resolve(timestamp + ".jsonl");
            writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            sessionStartMs = System.currentTimeMillis();

            JsonObject entry = new JsonObject();
            entry.addProperty("event", "session_start");
            entry.addProperty("timestamp", System.currentTimeMillis());
            entry.addProperty("file", logFile.toString());
            writeLine(entry);

            LOGGER.info("Agent logger started: {}", logFile);
        } catch (IOException e) {
            LOGGER.error("Failed to start agent logger", e);
        }
    }

    /**
     * Log an action execution with full details.
     */
    public void logAction(String action, JsonObject params, JsonObject result, long durationMs) {
        JsonObject entry = new JsonObject();
        entry.addProperty("event", "action");
        entry.addProperty("timestamp", System.currentTimeMillis());
        entry.addProperty("session_elapsed_ms", System.currentTimeMillis() - sessionStartMs);
        entry.addProperty("action", action);
        entry.add("params", sanitizeParams(params));
        entry.add("result", result);
        entry.addProperty("duration_ms", durationMs);
        entry.addProperty("ok", result.has("ok") && result.get("ok").getAsBoolean());
        writeLine(entry);
    }

    /**
     * Log a sub-step within an area/sequence action (detailed per-position/per-step info).
     */
    public void logSubStep(String parentAction, int stepIndex, String detail, boolean ok, String error) {
        JsonObject entry = new JsonObject();
        entry.addProperty("event", "substep");
        entry.addProperty("timestamp", System.currentTimeMillis());
        entry.addProperty("parent_action", parentAction);
        entry.addProperty("step_index", stepIndex);
        entry.addProperty("detail", detail);
        entry.addProperty("ok", ok);
        if (error != null) {
            entry.addProperty("error", error);
        }
        writeLine(entry);
    }

    /**
     * Log agent spawn/despawn events.
     */
    public void logEvent(String eventName, JsonObject data) {
        JsonObject entry = new JsonObject();
        entry.addProperty("event", eventName);
        entry.addProperty("timestamp", System.currentTimeMillis());
        if (data != null) {
            entry.add("data", data);
        }
        writeLine(entry);
    }

    /**
     * End the session.
     */
    public void endSession() {
        if (writer == null) return;
        try {
            JsonObject entry = new JsonObject();
            entry.addProperty("event", "session_end");
            entry.addProperty("timestamp", System.currentTimeMillis());
            entry.addProperty("duration_ms", System.currentTimeMillis() - sessionStartMs);
            writeLine(entry);
            writer.close();
            writer = null;
            LOGGER.info("Agent logger stopped");
        } catch (IOException e) {
            LOGGER.error("Failed to close agent logger", e);
        }
    }

    private void writeLine(JsonObject entry) {
        if (writer == null) return;
        try {
            writer.write(GSON.toJson(entry));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            LOGGER.error("Failed to write agent log entry", e);
        }
    }

    /**
     * Remove bulky fields from params for logging (e.g., keep action name + key params).
     */
    private JsonObject sanitizeParams(JsonObject params) {
        // Clone and remove the 'action' field (redundant, already in entry)
        JsonObject clean = params.deepCopy();
        clean.remove("action");
        return clean;
    }
}
