package com.pyosechang.agent.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.network.AgentHttpServer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

public class RuntimeManager {
    private static final RuntimeManager INSTANCE = new RuntimeManager();
    private static final Logger LOGGER = LogUtils.getLogger();
    private Process process;
    private AgentHttpServer httpServer;
    private String sessionId;
    private boolean hasLaunched = false;

    public static RuntimeManager getInstance() { return INSTANCE; }

    public void setHttpServer(AgentHttpServer httpServer) { this.httpServer = httpServer; }

    public void launch(String message, CommandSourceStack source) {
        if (process != null && process.isAlive()) {
            source.sendFailure(Component.literal("[Agent] Runtime already running."));
            return;
        }

        int bridgePort = httpServer != null ? httpServer.getPort() : 0;
        if (bridgePort == 0) {
            source.sendFailure(Component.literal("[Agent] HTTP bridge not available."));
            return;
        }

        // Resolve agent-runtime path relative to game dir
        // In dev: gameDir = agent-mod/run/, so ../agent-runtime = agent-mod/agent-runtime
        Path runtimePath = FMLPaths.GAMEDIR.get().resolve("../agent-runtime").normalize();
        // Use node + compiled JS to avoid tsx cold start overhead
        String osName = System.getProperty("os.name", "").toLowerCase();
        String nodeCmd = osName.contains("win") ? "node.exe" : "node";

        Thread runtimeThread = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    nodeCmd, runtimePath.resolve("dist/index.js").toString()
                );
                pb.directory(runtimePath.toFile());
                pb.environment().put("AGENT_BRIDGE_PORT", String.valueOf(bridgePort));
                pb.environment().put("AGENT_MESSAGE", message);
                // Session continuity: same sessionId across commands
                if (sessionId == null) {
                    sessionId = UUID.randomUUID().toString();
                }
                pb.environment().put("AGENT_SESSION_ID", sessionId);
                pb.environment().put("AGENT_IS_RESUME", hasLaunched ? "true" : "false");
                hasLaunched = true;
                // Remove CLAUDECODE env var to allow nested Claude Code launch
                pb.environment().remove("CLAUDECODE");
                pb.redirectErrorStream(true);

                process = pb.start();
                LOGGER.info("Agent runtime launched (PID {})", process.pid());

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        LOGGER.info("[agent-runtime] {}", line);
                        relayToChat(line, source);
                    }
                }

                int exitCode = process.waitFor();
                LOGGER.info("Agent runtime exited with code {}", exitCode);
                // Only show error exits in chat — normal completion is silent
                if (exitCode != 0 && source.getServer() != null) {
                    source.getServer().execute(() -> {
                        source.sendFailure(Component.literal(
                            "[Agent] Runtime error (exit code " + exitCode + ")"));
                    });
                }
            } catch (Exception e) {
                LOGGER.error("Failed to launch agent runtime", e);
                if (source.getServer() != null) {
                    source.getServer().execute(() -> {
                        source.sendFailure(Component.literal("[Agent] Runtime error: " + e.getMessage()));
                    });
                }
            } finally {
                process = null;
            }
        }, "agent-runtime");
        runtimeThread.setDaemon(true);
        runtimeThread.start();
    }

    private void relayToChat(String line, CommandSourceStack source) {
        try {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "";

            switch (type) {
                case "thought" -> {
                    String text = json.get("text").getAsString();
                    // Split by literal \n (JSON escaped) and real newlines
                    String[] lines = text.split("\\\\n|\n");
                    int maxLines = 20;
                    int lineCount = Math.min(lines.length, maxLines);
                    for (int i = 0; i < lineCount; i++) {
                        String line2 = lines[i].trim();
                        if (line2.isEmpty()) continue;
                        String prefix = (i == 0) ? "[Agent] " : "  ";
                        sendChatLine(source, Component.literal(prefix + line2).withStyle(ChatFormatting.GRAY));
                    }
                    if (lines.length > maxLines) {
                        sendChatLine(source, Component.literal("  ...(" + (lines.length - maxLines) + " more lines)").withStyle(ChatFormatting.GRAY));
                    }
                }
                case "tool_call" -> {
                    String name = json.get("name").getAsString();
                    name = name.replace("mcp__agent-bridge__", "");
                    sendChatLine(source, Component.literal("[Agent] > " + name).withStyle(ChatFormatting.AQUA));
                }
                case "result" -> {
                    int turns = json.has("turns") ? json.get("turns").getAsInt() : 0;
                    sendChatLine(source, Component.literal(String.format("[Agent] Done (%d turns)", turns))
                        .withStyle(ChatFormatting.GREEN));
                }
                case "error" -> {
                    String err = json.has("message") ? json.get("message").getAsString() : "unknown";
                    sendChatLine(source, Component.literal("[Agent] Error: " + err).withStyle(ChatFormatting.RED));
                }
                default -> {}
            };
        } catch (Exception ignored) {
            // Not JSON — ignore
        }
    }

    private void sendChatLine(CommandSourceStack source, MutableComponent msg) {
        if (source.getServer() != null) {
            final MutableComponent finalMsg = msg;
            source.getServer().execute(() -> {
                source.sendSuccess(() -> finalMsg, false);
            });
        }
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            LOGGER.info("Agent runtime forcibly stopped");
            process = null;
        }
    }

    /** Reset session state (called on server stop). Next launch starts a fresh session. */
    public void resetSession() {
        sessionId = null;
        hasLaunched = false;
        LOGGER.info("Agent session reset");
    }
}
