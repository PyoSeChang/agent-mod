package com.pyosechang.agent.event;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumSet;
import java.util.Set;

public class ChatSubscriber implements EventSubscriber {
    private static final Set<EventType> HANDLED = EnumSet.of(
        EventType.CHAT, EventType.TEXT, EventType.ERROR,
        EventType.SPAWNED, EventType.DESPAWNED
    );

    private final MinecraftServer server;

    public ChatSubscriber(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (!HANDLED.contains(event.type())) return;

        server.execute(() -> {
            String prefix = "[" + event.agentName() + "] ";
            boolean isManager = "manager".equals(event.agentName());

            switch (event.type()) {
                case CHAT -> {
                    String text = event.data().has("text") ? event.data().get("text").getAsString() : "";
                    ChatFormatting color = isManager ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.WHITE;
                    String[] lines = text.split("\\\\n|\n");
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.isEmpty()) continue;
                        String p = (i == 0) ? prefix : "  ";
                        broadcast(Component.literal(p + line).withStyle(color));
                    }
                }
                case TEXT -> {
                    int turns = event.data().has("turns") ? event.data().get("turns").getAsInt() : 0;
                    broadcast(Component.literal(String.format(prefix + "Done (%d turns)", turns)).withStyle(ChatFormatting.GREEN));
                }
                case ERROR -> {
                    String err = event.data().has("message") ? event.data().get("message").getAsString() : "unknown";
                    broadcast(Component.literal(prefix + "Error: " + err).withStyle(ChatFormatting.RED));
                }
                case SPAWNED -> {
                    broadcast(Component.literal(prefix + "Spawned").withStyle(ChatFormatting.GREEN));
                }
                case DESPAWNED -> {
                    broadcast(Component.literal(prefix + "Despawned").withStyle(ChatFormatting.YELLOW));
                }
                default -> {}
            }
        });
    }

    private void broadcast(MutableComponent msg) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(msg);
        }
    }
}
