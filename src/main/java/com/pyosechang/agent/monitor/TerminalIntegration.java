package com.pyosechang.agent.monitor;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

public class TerminalIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean terminalModLoaded = false;

    public static void initialize() {
        terminalModLoaded = ModList.get().isLoaded("terminal");
        if (terminalModLoaded) {
            LOGGER.info("terminal-mod detected, agent monitoring integration available");
            // Phase 4+: Create dedicated terminal tab for agent logs
            // This would use terminal-mod's API to create a virtual terminal
        } else {
            LOGGER.info("terminal-mod not detected, using chat-only monitoring");
        }
    }

    public static boolean isTerminalModLoaded() { return terminalModLoaded; }

    public static void sendLog(String type, String message) {
        // Always buffer for chat
        MonitorLogBuffer.getInstance().add(type, message);

        // If terminal-mod available, also stream to terminal tab
        if (terminalModLoaded) {
            // Phase 4+: Stream ANSI-colored output to terminal tab
            // For now, chat monitor handles everything
        }
    }
}
