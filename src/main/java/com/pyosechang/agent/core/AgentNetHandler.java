package com.pyosechang.agent.core;

import com.mojang.logging.LogUtils;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * Mock network handler for AgentPlayer. Sets up a dummy Connection with
 * an EmbeddedChannel so that ServerPlayer.tick() doesn't NPE on packet sends.
 * All outgoing packets are absorbed; keep-alive checks are disabled.
 */
public class AgentNetHandler extends ServerGamePacketListenerImpl {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Creates a mock net handler and sets player.connection = this.
     */
    public AgentNetHandler(MinecraftServer server, ServerPlayer player) {
        super(server, createMockConnection(), player);
    }

    private static Connection createMockConnection() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        try {
            Field channelField = Connection.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            channelField.set(connection, new EmbeddedChannel());
        } catch (NoSuchFieldException e) {
            // Fallback: search by type for obfuscated environments
            LOGGER.warn("'channel' field not found on Connection, searching by type");
            boolean found = false;
            for (Field f : Connection.class.getDeclaredFields()) {
                if (Channel.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        f.set(connection, new EmbeddedChannel());
                        LOGGER.info("Set Connection channel via field: {}", f.getName());
                        found = true;
                        break;
                    } catch (Exception ex) {
                        LOGGER.error("Failed to set field {}", f.getName(), ex);
                    }
                }
            }
            if (!found) {
                LOGGER.error("Could not find any Channel field on Connection — agent packets may NPE");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to set Connection.channel via reflection", e);
        }
        return connection;
    }

    @Override
    public void tick() {
        // no-op: skip keep-alive checks and timeout disconnect
    }

    @Override
    public void send(Packet<?> packet) {
        // no-op: absorb outgoing packets
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        // no-op: absorb outgoing packets
    }
}
