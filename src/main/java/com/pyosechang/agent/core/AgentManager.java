package com.pyosechang.agent.core;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.event.AgentEvent;
import com.pyosechang.agent.event.EventBus;
import com.pyosechang.agent.event.EventType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    /**
     * @param pos spawn position. If null and config has bed, uses bed position.
     */
    public boolean spawn(String name, ServerLevel level, BlockPos pos) {
        if (agents.containsKey(name)) return false;

        // Load config for gamemode / bed
        AgentConfig config = AgentConfig.load(name);

        // If no explicit position and bed is set, use bed position
        boolean spawnFromBed = false;
        if (pos == null && config.hasBed()) {
            pos = new BlockPos(config.getBedX(), config.getBedY(), config.getBedZ());
            spawnFromBed = true;
        }
        if (pos == null) {
            // Fallback to world spawn
            pos = level.getSharedSpawnPos();
        }

        GameProfile profile = new GameProfile(
            UUID.nameUUIDFromBytes(("Agent_" + name).getBytes()), "[" + name + "]");

        // Create AgentPlayer (ServerPlayer subclass)
        AgentPlayer agentPlayer = new AgentPlayer(server, level, profile);
        agentPlayer.setConfig(config);

        // Set up mock network handler (sets agentPlayer.connection)
        new AgentNetHandler(server, agentPlayer);

        agentPlayer.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

        // Apply gamemode from config
        switch (config.getGamemode()) {
            case CREATIVE -> {
                agentPlayer.setInvulnerable(true);
                agentPlayer.setGameMode(GameType.CREATIVE);
                agentPlayer.getAbilities().mayfly = true;
                agentPlayer.getAbilities().flying = false;
            }
            case SURVIVAL, HARDCORE -> {
                agentPlayer.setInvulnerable(false);
                agentPlayer.setGameMode(GameType.SURVIVAL);
            }
        }

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

        loadInventory(name, agentPlayer);

        AgentContext ctx = new AgentContext(name, agentPlayer, persona);
        agents.put(name, ctx);

        // 1) PlayerInfo FIRST — client ignores spawn packets for unknown players
        ClientboundPlayerInfoUpdatePacket infoPacket =
            new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                List.of(agentPlayer));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(infoPacket);
        }

        // 2) Register entity in world — entity tracker may send spawn packets
        level.addNewPlayer(agentPlayer);

        // 3) Explicit spawn + data packets (in case tracker didn't cover all players)
        ClientboundAddPlayerPacket spawnPacket = new ClientboundAddPlayerPacket(agentPlayer);
        ClientboundSetEntityDataPacket dataPacket =
            new ClientboundSetEntityDataPacket(agentPlayer.getId(),
                agentPlayer.getEntityData().getNonDefaultValues());

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(spawnPacket);
            if (dataPacket.packedItems() != null) {
                player.connection.send(dataPacket);
            }
        }

        LOGGER.info("Agent '{}' spawned at {} {} {} (gamemode={})", name,
            pos.getX(), pos.getY(), pos.getZ(), config.getGamemode());

        // Bed spawn animation: appear sleeping, wake up after 1 tick
        if (spawnFromBed) {
            agentPlayer.setPose(Pose.SLEEPING);
            broadcastEntityData(agentPlayer);
            server.tell(new TickTask(server.getTickCount() + 1, () -> {
                agentPlayer.setPose(Pose.STANDING);
                broadcastEntityData(agentPlayer);
            }));
        }

        JsonObject data = new JsonObject();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        EventBus.getInstance().publish(AgentEvent.of(name, EventType.SPAWNED, data));

        return true;
    }

    public boolean despawn(String name) {
        AgentContext ctx = agents.remove(name);
        if (ctx == null) return false;

        ServerPlayer agentPlayer = ctx.getPlayer();
        AgentConfig config = ctx.getConfig();

        saveInventory(name, agentPlayer);

        // Bed despawn animation: teleport to bed, sleep, then remove after 1 tick
        if (config.hasBed()) {
            agentPlayer.teleportTo(config.getBedX() + 0.5, config.getBedY(), config.getBedZ() + 0.5);
            agentPlayer.setPose(Pose.SLEEPING);
            broadcastEntityData(agentPlayer);
            AgentAnimation.broadcast(new net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket(agentPlayer));

            // Delay removal by 1 tick so clients see the sleeping pose
            server.tell(new TickTask(server.getTickCount() + 1, () -> {
                removeAgentFromClients(agentPlayer);
                agentPlayer.discard();
            }));
        } else {
            removeAgentFromClients(agentPlayer);
            agentPlayer.discard();
        }

        LOGGER.info("Agent '{}' despawned", name);
        EventBus.getInstance().publish(AgentEvent.of(name, EventType.DESPAWNED));

        return true;
    }

    private void removeAgentFromClients(ServerPlayer agentPlayer) {
        ClientboundRemoveEntitiesPacket removePacket =
            new ClientboundRemoveEntitiesPacket(agentPlayer.getId());
        ClientboundPlayerInfoRemovePacket infoRemovePacket =
            new ClientboundPlayerInfoRemovePacket(List.of(agentPlayer.getUUID()));

        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.connection.send(removePacket);
                player.connection.send(infoRemovePacket);
            }
        }
    }

    private void broadcastEntityData(ServerPlayer agentPlayer) {
        var nonDefaults = agentPlayer.getEntityData().getNonDefaultValues();
        if (nonDefaults != null) {
            ClientboundSetEntityDataPacket dataPacket =
                new ClientboundSetEntityDataPacket(agentPlayer.getId(), nonDefaults);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.connection.send(dataPacket);
            }
        }
    }

    public void despawnAll() {
        for (String name : Set.copyOf(agents.keySet())) {
            despawn(name);
        }
    }

    /**
     * Send agent visibility packets to a newly joined player.
     */
    public void sendAgentInfoToPlayer(ServerPlayer player) {
        if (agents.isEmpty()) return;

        for (AgentContext ctx : agents.values()) {
            ServerPlayer agentPlayer = ctx.getPlayer();
            ClientboundPlayerInfoUpdatePacket infoPacket =
                new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                    List.of(agentPlayer));
            ClientboundAddPlayerPacket spawnPacket = new ClientboundAddPlayerPacket(agentPlayer);
            ClientboundSetEntityDataPacket dataPacket =
                new ClientboundSetEntityDataPacket(agentPlayer.getId(),
                    agentPlayer.getEntityData().getNonDefaultValues());

            player.connection.send(infoPacket);
            player.connection.send(spawnPacket);
            if (dataPacket.packedItems() != null) {
                player.connection.send(dataPacket);
            }
        }
    }

    public AgentContext getAgent(String name) { return agents.get(name); }
    public Collection<AgentContext> getAllAgents() { return agents.values(); }
    public Set<String> getAgentNames() { return agents.keySet(); }
    public boolean isSpawned(String name) { return agents.containsKey(name); }
    public int getAgentCount() { return agents.size(); }

    // --- Inventory persistence ---

    private Path getInventoryPath(String name) {
        return FMLPaths.GAMEDIR.get().resolve(".agent/agents/" + name + "/inventory.dat");
    }

    private void saveInventory(String name, ServerPlayer agent) {
        try {
            ListTag items = new ListTag();
            for (int i = 0; i < agent.getInventory().getContainerSize(); i++) {
                ItemStack stack = agent.getInventory().getItem(i);
                if (!stack.isEmpty()) {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("Slot", i);
                    stack.save(tag);
                    items.add(tag);
                }
            }
            CompoundTag root = new CompoundTag();
            root.put("Items", items);

            Path path = getInventoryPath(name);
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            net.minecraft.nbt.NbtIo.writeCompressed(root, tmp.toFile());
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);

            LOGGER.info("Saved inventory for '{}' ({} stacks)", name, items.size());
        } catch (IOException e) {
            LOGGER.error("Failed to save inventory for '{}'", name, e);
        }
    }

    private void loadInventory(String name, ServerPlayer agent) {
        Path path = getInventoryPath(name);
        if (!Files.exists(path)) return;
        try {
            CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(path.toFile());
            ListTag items = root.getList("Items", 10); // 10 = CompoundTag type
            for (int i = 0; i < items.size(); i++) {
                CompoundTag tag = items.getCompound(i);
                int slot = tag.getInt("Slot");
                ItemStack stack = ItemStack.of(tag);
                agent.getInventory().setItem(slot, stack);
            }
            LOGGER.info("Loaded inventory for '{}' ({} stacks)", name, items.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load inventory for '{}'", name, e);
        }
    }
}
