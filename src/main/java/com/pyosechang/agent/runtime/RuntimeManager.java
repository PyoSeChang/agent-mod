package com.pyosechang.agent.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.core.AgentContext;
import com.pyosechang.agent.core.AgentManager;
import com.pyosechang.agent.event.AgentEvent;
import com.pyosechang.agent.event.EventBus;
import com.pyosechang.agent.event.EventType;
import com.pyosechang.agent.network.AgentHttpServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
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

        Path gameDir = FMLPaths.GAMEDIR.get();
        // Dev: agent-runtime is sibling to run/ dir; Deployed: inside game dir
        Path runtimeCandidate = gameDir.resolve("agent-runtime");
        if (!runtimeCandidate.toFile().isDirectory()) {
            runtimeCandidate = gameDir.resolve("../agent-runtime").normalize();
        }
        final Path runtimePath = runtimeCandidate;
        String nodeCmd = resolveNodeCommand();

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
                EventBus.getInstance().publish(AgentEvent.of(agentName, EventType.RUNTIME_STARTED));

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
                EventBus.getInstance().publish(AgentEvent.of(agentName, EventType.RUNTIME_STOPPED));
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

            switch (type) {
                case "thought" -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("text", json.get("text").getAsString());
                    EventBus.getInstance().publish(AgentEvent.of(agentName, EventType.THOUGHT, data));
                }
                case "tool_call" -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("name", json.get("name").getAsString());
                    EventBus.getInstance().publish(AgentEvent.of(agentName, EventType.TOOL_CALL, data));
                }
                case "chat" -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("text", json.get("text").getAsString());
                    EventBus.getInstance().publish(AgentEvent.of(agentName, EventType.CHAT, data));
                }
                case "result" -> {
                    JsonObject data = new JsonObject();
                    if (json.has("turns")) data.addProperty("turns", json.get("turns").getAsInt());
                    EventBus.getInstance().publish(AgentEvent.of(agentName, EventType.TEXT, data));
                }
                case "error" -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("message", json.has("message") ? json.get("message").getAsString() : "unknown");
                    EventBus.getInstance().publish(AgentEvent.of(agentName, EventType.ERROR, data));
                }
                default -> {}
            };
        } catch (Exception ignored) {
        }
    }

    static String resolveNodeCommand() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = osName.contains("win");
        String cmd = isWindows ? "node.exe" : "node";

        // Try PATH first
        try {
            Process test = new ProcessBuilder(cmd, "--version")
                .redirectErrorStream(true).start();
            int exit = test.waitFor();
            if (exit == 0) return cmd;
        } catch (Exception ignored) {}

        if (isWindows) {
            // Try 'where' command — finds node even if current PATH is incomplete
            try {
                Process where = new ProcessBuilder("C:\\Windows\\System32\\where.exe", "node.exe")
                    .redirectErrorStream(true).start();
                String output = new String(where.getInputStream().readAllBytes()).trim();
                int exit = where.waitFor();
                if (exit == 0 && !output.isEmpty()) {
                    String found = output.lines().findFirst().orElse("").trim();
                    if (!found.isEmpty() && new java.io.File(found).isFile()) {
                        LOGGER.info("Found node via 'where': {}", found);
                        return found;
                    }
                }
            } catch (Exception ignored) {}

            // Check common install locations
            java.util.List<String> candidates = new java.util.ArrayList<>();
            String programFiles = System.getenv("ProgramFiles");
            String programFilesX86 = System.getenv("ProgramFiles(x86)");
            String localAppData = System.getenv("LOCALAPPDATA");
            String appData = System.getenv("APPDATA");
            String userHome = System.getProperty("user.home");

            if (programFiles != null) candidates.add(programFiles + "\\nodejs\\node.exe");
            if (programFilesX86 != null) candidates.add(programFilesX86 + "\\nodejs\\node.exe");
            if (localAppData != null) candidates.add(localAppData + "\\Programs\\node\\node.exe");
            if (appData != null) candidates.add(appData + "\\fnm\\aliases\\default\\node.exe");
            if (appData != null) candidates.add(appData + "\\nvm\\current\\node.exe");
            if (localAppData != null) candidates.add(localAppData + "\\volta\\bin\\node.exe");
            if (userHome != null) candidates.add(userHome + "\\.nvm\\current\\node.exe");
            // Absolute fallback
            candidates.add("C:\\Program Files\\nodejs\\node.exe");

            for (String path : candidates) {
                if (new java.io.File(path).isFile()) {
                    LOGGER.info("Found node at: {}", path);
                    return path;
                }
            }
        } else {
            // Unix: check common locations
            String[] candidates = {"/usr/local/bin/node", "/usr/bin/node", "/opt/homebrew/bin/node"};
            for (String path : candidates) {
                if (new java.io.File(path).isFile()) {
                    LOGGER.info("Found node at: {}", path);
                    return path;
                }
            }
        }

        LOGGER.warn("Could not resolve node path, falling back to '{}'", cmd);
        return cmd;
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
