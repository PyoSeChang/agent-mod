package com.pyosechang.agent.core;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class FakePlayerManager {
    private static final FakePlayerManager INSTANCE = new FakePlayerManager();
    private static final Logger LOGGER = LogUtils.getLogger();
    private FakePlayer agent;
    private MinecraftServer server;

    public static FakePlayerManager getInstance() { return INSTANCE; }

    public void setServer(MinecraftServer server) { this.server = server; }
    public MinecraftServer getServer() { return server; }

    public boolean spawn(ServerLevel level, BlockPos pos) {
        if (agent != null) return false;
        GameProfile profile = new GameProfile(
            UUID.nameUUIDFromBytes("AgentBot".getBytes()), "[Agent]");
        agent = FakePlayerFactory.get(level, profile);
        agent.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        agent.setInvulnerable(true);

        // Send PlayerInfo packet FIRST — client ignores spawn packets
        // for players not in its player info (tab) list
        ClientboundPlayerInfoUpdatePacket infoPacket =
            new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                List.of(agent));

        // Then send spawn + entity data packets
        ClientboundAddPlayerPacket spawnPacket = new ClientboundAddPlayerPacket(agent);
        ClientboundSetEntityDataPacket dataPacket =
            new ClientboundSetEntityDataPacket(agent.getId(),
                agent.getEntityData().getNonDefaultValues());

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(infoPacket);
            player.connection.send(spawnPacket);
            if (dataPacket.packedItems() != null) {
                player.connection.send(dataPacket);
            }
        }

        LOGGER.info("Agent spawned at {} {} {}", pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    public boolean despawn() {
        if (agent == null) return false;

        // Remove from clients: entity + player info
        ClientboundRemoveEntitiesPacket removePacket =
            new ClientboundRemoveEntitiesPacket(agent.getId());
        ClientboundPlayerInfoRemovePacket infoRemovePacket =
            new ClientboundPlayerInfoRemovePacket(List.of(agent.getUUID()));

        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.connection.send(removePacket);
                player.connection.send(infoRemovePacket);
            }
        }

        agent.discard();
        agent = null;
        LOGGER.info("Agent despawned");
        return true;
    }

    public boolean isSpawned() { return agent != null; }
    public FakePlayer getAgent() { return agent; }
}
