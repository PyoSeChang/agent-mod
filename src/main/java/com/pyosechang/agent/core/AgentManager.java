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

import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AgentManager {
    private static final AgentManager INSTANCE = new AgentManager();
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ConcurrentHashMap<String, AgentContext> agents = new ConcurrentHashMap<>();
    private MinecraftServer server;

    public static AgentManager getInstance() { return INSTANCE; }

    public void setServer(MinecraftServer server) { this.server = server; }
    public MinecraftServer getServer() { return server; }

    public boolean spawn(String name, ServerLevel level, BlockPos pos) {
        if (agents.containsKey(name)) return false;

        GameProfile profile = new GameProfile(
            UUID.nameUUIDFromBytes(("Agent_" + name).getBytes()), "[" + name + "]");
        FakePlayer fakePlayer = FakePlayerFactory.get(level, profile);
        fakePlayer.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        fakePlayer.setInvulnerable(true);

        // Load persona from .agent/agents/{name}/PERSONA.md
        Path personaFile = FMLPaths.GAMEDIR.get().resolve(".agent/agents/" + name + "/PERSONA.md");
        PersonaConfig persona;
        if (personaFile.toFile().exists()) {
            persona = PersonaConfig.parse(name, personaFile);
            LOGGER.info("Loaded persona for '{}': role={}, tools={}", name, persona.getRole(),
                persona.isAllToolsAllowed() ? "all" : persona.getToolsCsv());
        } else {
            persona = PersonaConfig.defaultPersona(name);
            LOGGER.info("No PERSONA.md for '{}', using defaults", name);
        }

        AgentContext ctx = new AgentContext(name, fakePlayer, persona);
        agents.put(name, ctx);

        // Send visibility packets to all online players
        ClientboundPlayerInfoUpdatePacket infoPacket =
            new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                List.of(fakePlayer));
        ClientboundAddPlayerPacket spawnPacket = new ClientboundAddPlayerPacket(fakePlayer);
        ClientboundSetEntityDataPacket dataPacket =
            new ClientboundSetEntityDataPacket(fakePlayer.getId(),
                fakePlayer.getEntityData().getNonDefaultValues());

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(infoPacket);
            player.connection.send(spawnPacket);
            if (dataPacket.packedItems() != null) {
                player.connection.send(dataPacket);
            }
        }

        LOGGER.info("Agent '{}' spawned at {} {} {}", name, pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    public boolean despawn(String name) {
        AgentContext ctx = agents.remove(name);
        if (ctx == null) return false;

        FakePlayer fakePlayer = ctx.getFakePlayer();
        ClientboundRemoveEntitiesPacket removePacket =
            new ClientboundRemoveEntitiesPacket(fakePlayer.getId());
        ClientboundPlayerInfoRemovePacket infoRemovePacket =
            new ClientboundPlayerInfoRemovePacket(List.of(fakePlayer.getUUID()));

        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.connection.send(removePacket);
                player.connection.send(infoRemovePacket);
            }
        }

        fakePlayer.discard();
        LOGGER.info("Agent '{}' despawned", name);
        return true;
    }

    public void despawnAll() {
        for (String name : Set.copyOf(agents.keySet())) {
            despawn(name);
        }
    }

    public AgentContext getAgent(String name) { return agents.get(name); }
    public Collection<AgentContext> getAllAgents() { return agents.values(); }
    public Set<String> getAgentNames() { return agents.keySet(); }
    public boolean isSpawned(String name) { return agents.containsKey(name); }
    public int getAgentCount() { return agents.size(); }
}
