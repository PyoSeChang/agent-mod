package com.pyosechang.agent.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.core.AgentContext;
import com.pyosechang.agent.core.AgentManager;
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

public class RuntimeManager {
    private static final RuntimeManager INSTANCE = new RuntimeManager();
    private static final Logger LOGGER = LogUtils.getLogger();
    private AgentHttpServer httpServer;

    public static RuntimeManager getInstance() { return INSTANCE; }

    public void setHttpServer(AgentHttpServer httpServer) { this.httpServer = httpServer; }

    public void launch(String agentName, String message, CommandSourceStack source) {
        AgentContext ctx = AgentManager.getInstance().getAgent(agentName);
        if (ctx == null) {
            source.sendFailure(Component.literal("[" + agentName + "] Not spawned."));
            return;
        }

        if (ctx.isRuntimeRunning()) {
            source.sendFailure(Component.literal("[" + agentName + "] Runtime already running."));
            return;
        }

        int bridgePort = httpServer != null ? httpServer.getPort() : 0;
        if (bridgePort == 0) {
            source.sendFailure(Component.literal("[" + agentName + "] HTTP bridge not available."));
            return;
        }

        Path runtimePath = FMLPaths.GAMEDIR.get().resolve("../agent-runtime").normalize();
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
                pb.environment().put("AGENT_NAME", agentName);
                pb.environment().put("AGENT_SESSION_ID", ctx.getSessionId());
                pb.environment().put("AGENT_IS_RESUME", ctx.hasLaunched() ? "true" : "false");
                // Persona
                pb.environment().put("AGENT_PERSONA_CONTENT", ctx.getPersona().getRawContent());
                String toolsCsv = ctx.getPersona().getToolsCsv();
                if (!toolsCsv.isEmpty()) {
                    pb.environment().put("AGENT_TOOLS", toolsCsv);
                }
                ctx.setHasLaunched(true);
                pb.environment().remove("CLAUDECODE");
                pb.redirectErrorStream(true);

                Process process = pb.start();
                ctx.setRuntimeProcess(process);
                LOGGER.info("Agent '{}' runtime launched (PID {})", agentName, process.pid());

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        LOGGER.info("[agent-runtime:{}] {}", agentName, line);
                        relayToChat(agentName, line, source);
                    }
                }

                int exitCode = process.waitFor();
                LOGGER.info("Agent '{}' runtime exited with code {}", agentName, exitCode);
                if (exitCode != 0 && !ctx.isStoppedByUser()) {
                    // Reset session on genuine crash to prevent corrupted resume
                    ctx.resetSession();
                    LOGGER.info("Agent '{}' session reset due to crash (code {})", agentName, exitCode);
                    if (source.getServer() != null) {
                        source.getServer().execute(() -> {
                            source.sendFailure(Component.literal(
                                "[" + agentName + "] Runtime crash (exit code " + exitCode + ") — session reset"));
                        });
                    }
                } else if (exitCode != 0) {
                    // Stopped by user — keep session for resume
                    LOGGER.info("Agent '{}' runtime stopped by user", agentName);
                }
                ctx.setStoppedByUser(false);
            } catch (Exception e) {
                LOGGER.error("Failed to launch agent runtime for '{}'", agentName, e);
                if (source.getServer() != null) {
                    source.getServer().execute(() -> {
                        source.sendFailure(Component.literal(
                            "[" + agentName + "] Runtime error: " + e.getMessage()));
                    });
                }
            } finally {
                ctx.setRuntimeProcess(null);
            }
        }, "agent-runtime-" + agentName);
        runtimeThread.setDaemon(true);
        runtimeThread.start();
    }

    private void relayToChat(String agentName, String line, CommandSourceStack source) {
        try {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "";
            String prefix = "[" + agentName + "] ";

            switch (type) {
                case "thought" -> {
                    String text = json.get("text").getAsString();
                    String[] lines = text.split("\\\\n|\n");
                    int maxLines = 20;
                    int lineCount = Math.min(lines.length, maxLines);
                    for (int i = 0; i < lineCount; i++) {
                        String line2 = lines[i].trim();
                        if (line2.isEmpty()) continue;
                        String p = (i == 0) ? prefix : "  ";
                        sendChatLine(source, Component.literal(p + line2).withStyle(ChatFormatting.GRAY));
                    }
                    if (lines.length > maxLines) {
                        sendChatLine(source, Component.literal("  ...(" + (lines.length - maxLines) + " more lines)").withStyle(ChatFormatting.GRAY));
                    }
                }
                case "tool_call" -> {
                    String name = json.get("name").getAsString();
                    name = name.replace("mcp__agent-bridge__", "");
                    sendChatLine(source, Component.literal(prefix + "> " + name).withStyle(ChatFormatting.AQUA));
                }
                case "result" -> {
                    int turns = json.has("turns") ? json.get("turns").getAsInt() : 0;
                    sendChatLine(source, Component.literal(String.format(prefix + "Done (%d turns)", turns))
                        .withStyle(ChatFormatting.GREEN));
                }
                case "error" -> {
                    String err = json.has("message") ? json.get("message").getAsString() : "unknown";
                    sendChatLine(source, Component.literal(prefix + "Error: " + err).withStyle(ChatFormatting.RED));
                }
                default -> {}
            };
        } catch (Exception ignored) {
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

    public void stop(String agentName) {
        AgentContext ctx = AgentManager.getInstance().getAgent(agentName);
        if (ctx != null && ctx.isRuntimeRunning()) {
            ctx.setStoppedByUser(true);
            ctx.getRuntimeProcess().destroyForcibly();
            LOGGER.info("Agent '{}' runtime forcibly stopped", agentName);
            ctx.setRuntimeProcess(null);
        }
    }

    public void stopAll() {
        for (AgentContext ctx : AgentManager.getInstance().getAllAgents()) {
            if (ctx.isRuntimeRunning()) {
                ctx.setStoppedByUser(true);
                ctx.getRuntimeProcess().destroyForcibly();
                ctx.setRuntimeProcess(null);
            }
        }
        LOGGER.info("All agent runtimes stopped");
    }

    public void resetAllSessions() {
        for (AgentContext ctx : AgentManager.getInstance().getAllAgents()) {
            ctx.resetSession();
        }
        LOGGER.info("All agent sessions reset");
    }
}
