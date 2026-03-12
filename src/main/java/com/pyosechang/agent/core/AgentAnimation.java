package com.pyosechang.agent.core;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.common.util.FakePlayer;

/**
 * Utility for FakePlayer visual animations — look-at, arm swing, position broadcast.
 * All methods broadcast packets to real players so they can see agent actions.
 */
public final class AgentAnimation {

    private AgentAnimation() {}

    /**
     * Rotate the agent to look at (x, y, z) and broadcast the rotation to all clients.
     */
    public static void lookAt(FakePlayer agent, double x, double y, double z) {
        double dx = x - agent.getX();
        double dy = y - (agent.getY() + agent.getEyeHeight());
        double dz = z - agent.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(Mth.atan2(dy, horizontalDist) * (180.0 / Math.PI));

        agent.setYRot(yaw);
        agent.setXRot(pitch);
        agent.setYHeadRot(yaw);

        // Body rotation + position
        broadcast(new ClientboundTeleportEntityPacket(agent));
        // Head rotation (separate packet required for FakePlayer)
        broadcast(new ClientboundRotateHeadPacket(agent, (byte) (yaw * 256.0F / 360.0F)));
    }

    /**
     * Play the main-hand swing animation for all clients.
     */
    public static void swingArm(FakePlayer agent) {
        broadcast(new ClientboundAnimatePacket(agent, 0));
    }

    /**
     * Broadcast a packet to all real players on the server.
     */
    public static void broadcast(Packet<?> packet) {
        MinecraftServer server = FakePlayerManager.getInstance().getServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(packet);
        }
    }
}
