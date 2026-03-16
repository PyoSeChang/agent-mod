package com.pyosechang.agent.client;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Formats SSE events into styled Components for the monitor conversation log.
 * Direct port of agent-tui/event.go FormatEvent().
 */
public class EventFormatter {

    public static final ResourceLocation MONITOR_FONT = new ResourceLocation("agent", "monitor");
    private static final Style FONT = Style.EMPTY.withFont(MONITOR_FONT);

    // Colors matching Go TUI's lipgloss palette
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM_GRAY = 0xFF808080;
    private static final int VERY_DIM = 0xFF505050;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int META = 0xFF6C6C6C;
    private static final int MAGENTA = 0xFFAA00FF;

    private static final Set<String> ALWAYS_VISIBLE = Set.of(
        "CHAT", "TEXT", "ERROR", "SPAWNED", "DESPAWNED",
        "TOOL_CALL", "ACTION_STARTED", "ACTION_COMPLETED", "ACTION_FAILED"
    );

    public static boolean isVisibleEvent(String type, boolean verbose) {
        if (ALWAYS_VISIBLE.contains(type)) return true;
        return verbose;
    }

    public static List<Component> formatEvent(String type, JsonObject eventJson) {
        JsonObject data = eventJson.has("data") && eventJson.get("data").isJsonObject()
                ? eventJson.getAsJsonObject("data") : new JsonObject();

        return switch (type) {
            case "CHAT" -> formatChat(data);
            case "THOUGHT" -> formatThought(data);
            case "TOOL_CALL" -> formatToolCall(data);
            case "TOOL_RESULT" -> formatToolResult(data);
            case "TEXT" -> formatText(data);
            case "ERROR" -> formatError(data);
            case "RUNTIME_STARTED" -> lines(styled("  \u25CF runtime started", META));
            case "RUNTIME_STOPPED" -> lines(styled("  \u25CB runtime stopped", META));
            case "SPAWNED" -> lines(styled("  \u2191 spawned", GREEN));
            case "DESPAWNED" -> lines(styled("  \u2193 despawned", META));
            case "ACTION_STARTED" -> formatAction(data, "\u23F5", DIM_GRAY);
            case "ACTION_COMPLETED" -> formatAction(data, "\u2713", META);
            case "ACTION_FAILED" -> formatActionFailed(data);
            case "SCHEDULE_TRIGGERED" -> formatSchedule(data);
            case "OBSERVER_FIRED" -> formatObserver(data);
            case "PAUSED" -> lines(styled("  \u23F8 paused", META));
            case "RESUMED" -> lines(styled("  \u25B6 resumed", META));
            default -> lines(styled("  " + type, META));
        };
    }

    public static List<Component> formatUserInput(String text) {
        List<Component> result = new ArrayList<>();
        result.add(Component.empty());
        result.add(styled("> " + text, WHITE, true));
        result.add(Component.empty());
        return result;
    }

    // --- Private formatting methods ---

    private static List<Component> formatChat(JsonObject data) {
        String text = str(data, "text");
        String[] parts = text.split("\n", -1);
        List<Component> result = new ArrayList<>();
        result.add(Component.empty());
        for (String line : parts) {
            result.add(styled("  " + line, WHITE));
        }
        return result;
    }

    private static List<Component> formatThought(JsonObject data) {
        String text = str(data, "text");
        String firstLine = text.contains("\n") ? text.substring(0, text.indexOf('\n')) : text;
        if (firstLine.length() > 100) firstLine = firstLine.substring(0, 97) + "...";
        return lines(styled("  \u28FF " + firstLine, VERY_DIM));
    }

    private static List<Component> formatToolCall(JsonObject data) {
        String name = stripMcpPrefix(str(data, "name"));
        return lines(styled("  \u23F5 " + name, DIM_GRAY));
    }

    private static List<Component> formatToolResult(JsonObject data) {
        String name = stripMcpPrefix(str(data, "name"));
        boolean success = !data.has("success") || data.get("success").getAsBoolean();
        if (success) {
            return lines(styled("  \u2713 " + name, META));
        }
        return lines(styled("  \u2717 " + name, RED));
    }

    private static List<Component> formatText(JsonObject data) {
        String turns = "";
        if (data.has("turns")) {
            turns = " (" + data.get("turns").getAsInt() + " turns)";
        }
        List<Component> result = new ArrayList<>();
        result.add(Component.empty());
        result.add(styled("  \u2713 Done" + turns, GREEN));
        result.add(Component.empty());
        return result;
    }

    private static List<Component> formatError(JsonObject data) {
        String msg = str(data, "message");
        if (msg.isEmpty()) msg = "unknown";
        return lines(styled("  \u2717 " + msg, RED));
    }

    private static List<Component> formatAction(JsonObject data, String symbol, int color) {
        String action = str(data, "action");
        return lines(styled("  " + symbol + " " + action, color));
    }

    private static List<Component> formatActionFailed(JsonObject data) {
        String action = str(data, "action");
        String reason = str(data, "error");
        String s = "  \u2717 " + action;
        if (!reason.isEmpty()) s += " — " + reason;
        return lines(styled(s, RED));
    }

    private static List<Component> formatSchedule(JsonObject data) {
        String title = str(data, "title");
        String target = str(data, "targetAgent");
        return lines(styled("  \u23F0 " + title + " \u2192 " + target, MAGENTA));
    }

    private static List<Component> formatObserver(JsonObject data) {
        String evType = str(data, "eventType");
        return lines(styled("  \uD83D\uDC41 " + evType, MAGENTA));
    }

    // --- Utilities ---

    private static MutableComponent styled(String text, int color) {
        return Component.literal(text).withStyle(FONT.withColor(color));
    }

    private static MutableComponent styled(String text, int color, boolean bold) {
        return Component.literal(text).withStyle(FONT.withColor(color).withBold(bold));
    }

    private static List<Component> lines(Component... components) {
        return List.of(components);
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : "";
    }

    private static String stripMcpPrefix(String name) {
        int idx = name.lastIndexOf("__");
        return idx >= 0 ? name.substring(idx + 2) : name;
    }
}
