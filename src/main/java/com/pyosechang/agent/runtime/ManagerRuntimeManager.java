package com.pyosechang.agent.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.core.schedule.ManagerContext;
import com.pyosechang.agent.core.schedule.ScheduleManager;
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
            String prefix = "[Manager] ";

            switch (type) {
                case "thought" -> {
                    String text = json.get("text").getAsString();
                    String[] lines = text.split("\\\\n|\n");
                    int maxLines = 20;
                    int lineCount = Math.min(lines.length, maxLines);
                    for (int i = 0; i < lineCount; i++) {
                        String l = lines[i].trim();
                        if (l.isEmpty()) continue;
                        String p = (i == 0) ? prefix : "  ";
                        sendChatLine(source, Component.literal(p + l).withStyle(ChatFormatting.LIGHT_PURPLE));
                    }
                }
                case "tool_call" -> {
                    String name = json.get("name").getAsString();
                    name = name.replace("mcp__manager-bridge__", "");
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
        } catch (Exception ignored) {}
    }

    private void sendChatLine(CommandSourceStack source, MutableComponent msg) {
        if (source.getServer() != null) {
            final MutableComponent finalMsg = msg;
            source.getServer().execute(() -> {
                source.sendSuccess(() -> finalMsg, false);
            });
        }
    }
}
