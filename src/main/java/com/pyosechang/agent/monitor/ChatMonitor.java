package com.pyosechang.agent.monitor;

import com.pyosechang.agent.AgentMod;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = AgentMod.MOD_ID)
public class ChatMonitor {
    private static MinecraftServer server;
    private static int tickCounter = 0;

    public static void setServer(MinecraftServer s) { server = s; }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;

        // Check every 10 ticks (0.5 sec) to avoid spam
        if (++tickCounter % 10 != 0) return;

        List<MonitorLogBuffer.LogEntry> entries = MonitorLogBuffer.getInstance().getNew();
        for (MonitorLogBuffer.LogEntry entry : entries) {
            sendFormattedEntry(entry);
        }
    }

    private static final int MAX_CHAT_LINE_LENGTH = 60;

    private static void sendFormattedEntry(MonitorLogBuffer.LogEntry entry) {
        ChatFormatting color = switch (entry.type()) {
            case "thought" -> ChatFormatting.GRAY;
            case "action" -> ChatFormatting.WHITE;
            case "observation" -> ChatFormatting.AQUA;
            case "intervention" -> ChatFormatting.YELLOW;
            case "error" -> ChatFormatting.RED;
            case "complete" -> ChatFormatting.GREEN;
            default -> ChatFormatting.WHITE;
        };

        String prefix = switch (entry.type()) {
            case "thought" -> "[Agent\u00b7Think] ";
            case "action" -> "[Agent\u00b7Act] ";
            case "observation" -> "[Agent\u00b7Obs] ";
            case "intervention" -> "[Agent\u00b7Input] ";
            case "error" -> "[Agent\u00b7Err] ";
            case "complete" -> "[Agent\u00b7Done] ";
            default -> "[Agent] ";
        };

        String text = entry.message();
        // Split by existing newlines first, then wrap long lines
        String[] paragraphs = text.split("\n");
        boolean firstLine = true;
        for (String paragraph : paragraphs) {
            String linePrefix = firstLine ? prefix : "  ";
            List<String> wrapped = wrapText(linePrefix + paragraph, MAX_CHAT_LINE_LENGTH);
            for (String line : wrapped) {
                MutableComponent msg = Component.literal(line).withStyle(color);
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    player.sendSystemMessage(msg);
                }
            }
            firstLine = false;
        }
    }

    private static List<String> wrapText(String text, int maxLength) {
        List<String> lines = new java.util.ArrayList<>();
        int safety = 0;
        while (text.length() > maxLength && safety++ < 100) {
            // Try to break at a space
            int breakAt = text.lastIndexOf(' ', maxLength);
            if (breakAt <= 0) breakAt = maxLength;
            lines.add(text.substring(0, breakAt));
            text = text.substring(breakAt).stripLeading();
            if (!text.isEmpty()) text = "  " + text;
        }
        if (!text.isEmpty()) lines.add(text);
        return lines;
    }
}
