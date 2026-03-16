package com.pyosechang.agent.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.core.schedule.ManagerContext;
import com.pyosechang.agent.core.schedule.ScheduleManager;
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

/**
 * Manages the Agent Manager Node.js runtime process.
 * The manager is a persistent runtime that handles schedule orchestration.
 */
public class ManagerRuntimeManager {
    private static final ManagerRuntimeManager INSTANCE = new ManagerRuntimeManager();
    private static final Logger LOGGER = LogUtils.getLogger();
    private AgentHttpServer httpServer;

    public static ManagerRuntimeManager getInstance() { return INSTANCE; }

    public void setHttpServer(AgentHttpServer httpServer) { this.httpServer = httpServer; }

    /**
     * Launch or message the manager runtime.
     * If already running, adds message to intervention queue.
     * If not running, starts the runtime with the message.
     */
    public void launchOrMessage(String message, CommandSourceStack source) {
        ManagerContext ctx = ScheduleManager.getInstance().getManagerContext();
        if (ctx == null) {
            source.sendFailure(Component.literal("[Manager] Not initialized."));
            return;
        }

        if (ctx.isRuntimeRunning()) {
            // Already running — add to intervention queue
            ctx.getInterventionQueue().add(message);
            source.sendSuccess(() -> Component.literal("[Manager] Message queued (runtime active)"), false);
            return;
        }

        int bridgePort = httpServer != null ? httpServer.getPort() : 0;
        if (bridgePort == 0) {
            source.sendFailure(Component.literal("[Manager] HTTP bridge not available."));
            return;
        }

        Path runtimePath = FMLPaths.GAMEDIR.get().resolve("../agent-runtime").normalize();
        String osName = System.getProperty("os.name", "").toLowerCase();
        String nodeCmd = osName.contains("win") ? "node.exe" : "node";

        Thread runtimeThread = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    nodeCmd, runtimePath.resolve("dist/manager-index.js").toString()
                );
                pb.directory(runtimePath.toFile());
                pb.environment().put("MANAGER_BRIDGE_PORT", String.valueOf(bridgePort));
                pb.environment().put("MANAGER_MESSAGE", message);
                pb.environment().put("MANAGER_SESSION_ID", ctx.getSessionId());
                pb.environment().put("MANAGER_IS_RESUME", ctx.hasLaunched() ? "true" : "false");
                ctx.setHasLaunched(true);
                pb.environment().remove("CLAUDECODE");
                pb.redirectErrorStream(true);

                Process process = pb.start();
                ctx.setRuntimeProcess(process);
                LOGGER.info("Manager runtime launched (PID {})", process.pid());
                EventBus.getInstance().publish(AgentEvent.of("manager", EventType.RUNTIME_STARTED));

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        LOGGER.info("[manager-runtime] {}", line);
                        relayToChat(line, source);
                    }
                }

                int exitCode = process.waitFor();
                LOGGER.info("Manager runtime exited with code {}", exitCode);
                EventBus.getInstance().publish(AgentEvent.of("manager", EventType.RUNTIME_STOPPED));
                if (exitCode != 0 && source.getServer() != null) {
                    source.getServer().execute(() -> {
                        source.sendFailure(Component.literal(
                            "[Manager] Runtime error (exit code " + exitCode + ")"));
                    });
                }
            } catch (Exception e) {
                LOGGER.error("Failed to launch manager runtime", e);
                if (source.getServer() != null) {
                    source.getServer().execute(() -> {
                        source.sendFailure(Component.literal(
                            "[Manager] Runtime error: " + e.getMessage()));
                    });
                }
            } finally {
                ctx.setRuntimeProcess(null);
            }
        }, "manager-runtime");
        runtimeThread.setDaemon(true);
        runtimeThread.start();
    }

    public void stop() {
        ManagerContext ctx = ScheduleManager.getInstance().getManagerContext();
        if (ctx != null && ctx.isRuntimeRunning()) {
            ctx.getRuntimeProcess().destroyForcibly();
            ctx.setRuntimeProcess(null);
            LOGGER.info("Manager runtime forcibly stopped");
        }
    }

    private void relayToChat(String line, CommandSourceStack source) {
        try {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "";

            switch (type) {
                case "thought" -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("text", json.get("text").getAsString());
                    EventBus.getInstance().publish(AgentEvent.of("manager", EventType.THOUGHT, data));
                }
                case "tool_call" -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("name", json.get("name").getAsString());
                    EventBus.getInstance().publish(AgentEvent.of("manager", EventType.TOOL_CALL, data));
                }
                case "chat" -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("text", json.get("text").getAsString());
                    EventBus.getInstance().publish(AgentEvent.of("manager", EventType.CHAT, data));
                }
                case "result" -> {
                    JsonObject data = new JsonObject();
                    if (json.has("turns")) data.addProperty("turns", json.get("turns").getAsInt());
                    EventBus.getInstance().publish(AgentEvent.of("manager", EventType.TEXT, data));
                }
                case "error" -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("message", json.has("message") ? json.get("message").getAsString() : "unknown");
                    EventBus.getInstance().publish(AgentEvent.of("manager", EventType.ERROR, data));
                }
                default -> {}
            };
        } catch (Exception ignored) {}
    }
}
