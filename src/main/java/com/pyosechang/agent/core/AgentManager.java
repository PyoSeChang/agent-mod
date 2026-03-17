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
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.EquipmentSlot;
import com.mojang.datafixers.util.Pair;
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
     * Spawn (activate) agent. If dormant, wakes up. Otherwise creates new entity.
     * Position: bed → nearby player → world spawn.
     */
    public boolean spawn(String name, ServerLevel level) {
        // If dormant, wake up instead of creating new entity
        AgentContext existing = agents.get(name);
        if (existing != null && existing.isDormant()) {
            return wakeUp(existing);
        }
        if (existing != null) return false; // already active

        AgentConfig config = AgentConfig.load(name);

        // Position resolution
        BlockPos pos;
        boolean spawnFromBed = false;
        if (config.hasBed()) {
            pos = new BlockPos(config.getBedX(), config.getBedY(), config.getBedZ());
            spawnFromBed = true;
        } else if (server != null && !server.getPlayerList().getPlayers().isEmpty()) {
            ServerPlayer nearPlayer = server.getPlayerList().getPlayers().get(0);
            pos = nearPlayer.blockPosition().offset(2, 0, 0);
        } else {
            pos = level.getSharedSpawnPos();
        }

        AgentContext ctx = createAgentEntity(name, level, pos, config);
        agents.put(name, ctx);
        sendSpawnPackets(ctx.getPlayer());

        LOGGER.info("Agent '{}' spawned at {} {} {} (gamemode={})", name,
            pos.getX(), pos.getY(), pos.getZ(), config.getGamemode());

        // Bed spawn: appear sleeping, wake up after 1 tick
        if (spawnFromBed) {
            AgentPlayer ap = (AgentPlayer) ctx.getPlayer();
            ap.setPose(Pose.SLEEPING);
            broadcastEntityData(ap);
            server.tell(new TickTask(server.getTickCount() + 1, () -> {
                ap.setPose(Pose.STANDING);
                broadcastEntityData(ap);
            }));
        }

        JsonObject data = new JsonObject();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        EventBus.getInstance().publish(AgentEvent.of(name, EventType.SPAWNED, data));
        return true;
    }

    /**
     * Spawn a dormant (sleeping) agent at bed position.
     * Called on server start for agents with beds.
     */
    public void spawnDormant(String name, ServerLevel level) {
        if (agents.containsKey(name)) return;
        AgentConfig config = AgentConfig.load(name);
        if (!config.hasBed()) return;

        BlockPos pos = new BlockPos(config.getBedX(), config.getBedY(), config.getBedZ());

        // Only sleep if bed block actually exists
        boolean bedExists = level.getBlockState(pos).getBlock()
            instanceof net.minecraft.world.level.block.BedBlock;
        if (!bedExists) {
            LOGGER.warn("Bed for '{}' at {} no longer exists, skipping dormant spawn", name, pos);
            return;
        }

        AgentContext ctx = createAgentEntity(name, level, pos, config);
        ctx.setDormant(true);
        agents.put(name, ctx);

        sleepInBed(ctx.getPlayer(), pos);
        sendSpawnPackets(ctx.getPlayer());
        LOGGER.info("Agent '{}' dormant at bed {} {} {}", name, pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Wake a dormant agent — find stand-up position next to bed, resume ticking.
     * Replicates vanilla LivingEntity.stopSleeping() logic since our override is no-op.
     */
    private boolean wakeUp(AgentContext ctx) {
        ctx.setDormant(false);
        ServerPlayer ap = ctx.getPlayer();

        // Find stand-up position (vanilla wake-up logic)
        ap.getSleepingPos().ifPresent(bedPos -> {
            if (ap.level() instanceof ServerLevel level) {
                net.minecraft.world.level.block.state.BlockState bedState = level.getBlockState(bedPos);
                if (bedState.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                    // Set bed to unoccupied
                    level.setBlock(bedPos, bedState.setValue(
                        net.minecraft.world.level.block.BedBlock.OCCUPIED, false), 3);

                    // Find valid stand-up position next to bed
                    net.minecraft.core.Direction facing = bedState.getValue(
                        net.minecraft.world.level.block.BedBlock.FACING);
                    java.util.Optional<net.minecraft.world.phys.Vec3> standUp =
                        net.minecraft.world.level.block.BedBlock.findStandUpPosition(
                            net.minecraft.world.entity.EntityType.PLAYER, level, bedPos, facing, ap.getYRot());

                    if (standUp.isPresent()) {
                        net.minecraft.world.phys.Vec3 pos = standUp.get();
                        ap.setPos(pos.x, pos.y, pos.z);
                    } else {
                        // Fallback: on top of bed block
                        ap.setPos(bedPos.getX() + 0.5, bedPos.getY() + 0.6, bedPos.getZ() + 0.5);
                    }
                }
            }
        });

        // Clear sleeping state
        ap.clearSleepingPos();
        ap.setPose(Pose.STANDING);
        broadcastEntityData(ap);
        AgentAnimation.broadcast(
            new net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket(ap));
        LOGGER.info("Agent '{}' woke up from bed", ctx.getName());
        EventBus.getInstance().publish(AgentEvent.of(ctx.getName(), EventType.SPAWNED));
        return true;
    }

    /**
     * Create agent entity + context (shared by spawn and spawnDormant).
     */
    private AgentContext createAgentEntity(String name, ServerLevel level, BlockPos pos, AgentConfig config) {
        GameProfile profile = new GameProfile(
            UUID.nameUUIDFromBytes(("Agent_" + name).getBytes()), "[" + name + "]");

        AgentPlayer agentPlayer = new AgentPlayer(server, level, profile);
        agentPlayer.setConfig(config);
        new AgentNetHandler(server, agentPlayer);
        agentPlayer.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

        // Apply gamemode
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

        Path personaFile = FMLPaths.GAMEDIR.get().resolve(".agent/agents/" + name + "/PERSONA.md");
        PersonaConfig persona = personaFile.toFile().exists()
            ? PersonaConfig.parse(name, personaFile)
            : PersonaConfig.defaultPersona(name);

        loadInventory(name, agentPlayer);
        level.addNewPlayer(agentPlayer);

        return new AgentContext(name, agentPlayer, persona);
    }

    private void sendSpawnPackets(ServerPlayer agentPlayer) {
        ClientboundPlayerInfoUpdatePacket infoPacket =
            new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                List.of(agentPlayer));
        ClientboundAddPlayerPacket spawnPacket = new ClientboundAddPlayerPacket(agentPlayer);
        ClientboundSetEntityDataPacket dataPacket =
            new ClientboundSetEntityDataPacket(agentPlayer.getId(),
                agentPlayer.getEntityData().getNonDefaultValues());
        ClientboundSetEquipmentPacket equipPacket = buildEquipmentPacket(agentPlayer);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(infoPacket);
            player.connection.send(spawnPacket);
            if (dataPacket.packedItems() != null) {
                player.connection.send(dataPacket);
            }
            if (equipPacket != null) {
                player.connection.send(equipPacket);
            }
        }
    }

    /**
     * Despawn agent. If bed is set, go dormant (sleep in bed, entity stays).
     * If no bed, remove entity entirely.
     */
    public boolean despawn(String name) {
        AgentContext ctx = agents.get(name);
        if (ctx == null || ctx.isDormant()) return false;

        saveInventory(name, ctx.getPlayer());

        AgentConfig config = ctx.getConfig();
        if (config.hasBed()) {
            // Go dormant: teleport to bed, sleep in bed, keep entity
            BlockPos bedPos = new BlockPos(config.getBedX(), config.getBedY(), config.getBedZ());
            ServerPlayer ap = ctx.getPlayer();
            ap.teleportTo(bedPos.getX() + 0.5, bedPos.getY(), bedPos.getZ() + 0.5);
            AgentAnimation.broadcast(new net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket(ap));
            if (sleepInBed(ap, bedPos)) {
                ctx.setDormant(true);
            } else {
                // Bed block gone — remove entity
                agents.remove(name);
                removeAgentFromClients(ap);
                ap.discard();
            }
        } else {
            // No bed: remove entirely
            agents.remove(name);
            removeAgentFromClients(ctx.getPlayer());
            ctx.getPlayer().discard();
        }

        LOGGER.info("Agent '{}' despawned (dormant={})", name, config.hasBed());
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

    /**
     * Put agent to sleep in a bed using vanilla startSleeping().
     * Vanilla sleeps at the HEAD block, not the FOOT.
     * Config stores FOOT position; we calculate HEAD = FOOT + facing direction.
     */
    /**
     * Put agent to sleep in bed. Returns false if bed block doesn't exist.
     */
    private boolean sleepInBed(ServerPlayer agent, BlockPos footPos) {
        if (!(agent.level() instanceof ServerLevel level)) return false;
        net.minecraft.world.level.block.state.BlockState footState = level.getBlockState(footPos);
        if (!(footState.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) return false;
        if (!footState.hasProperty(net.minecraft.world.level.block.BedBlock.FACING)) return false;

        net.minecraft.core.Direction facing = footState.getValue(
            net.minecraft.world.level.block.BedBlock.FACING);
        BlockPos headPos = footPos.relative(facing);
        agent.startSleeping(headPos);
        broadcastEntityData(agent);
        return true;
    }

    /**
     * Build equipment packet for all non-empty slots. Returns null if no equipment.
     */
    static ClientboundSetEquipmentPacket buildEquipmentPacket(ServerPlayer agentPlayer) {
        List<Pair<EquipmentSlot, ItemStack>> equipment = new java.util.ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = agentPlayer.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                equipment.add(Pair.of(slot, stack.copy()));
            }
        }
        if (equipment.isEmpty()) return null;
        return new ClientboundSetEquipmentPacket(agentPlayer.getId(), equipment);
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

    /** Remove all agents from world (server shutdown). */
    public void despawnAll() {
        for (String name : Set.copyOf(agents.keySet())) {
            AgentContext ctx = agents.remove(name);
            if (ctx != null) {
                saveInventory(name, ctx.getPlayer());
                ctx.getPlayer().discard();
            }
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
            ClientboundSetEquipmentPacket equipPacket = buildEquipmentPacket(agentPlayer);

            player.connection.send(infoPacket);
            player.connection.send(spawnPacket);
            if (dataPacket.packedItems() != null) {
                player.connection.send(dataPacket);
            }
            if (equipPacket != null) {
                player.connection.send(equipPacket);
            }
        }
    }

    public AgentContext getAgent(String name) { return agents.get(name); }
    public Collection<AgentContext> getAllAgents() { return agents.values(); }
    public Set<String> getAgentNames() { return agents.keySet(); }
    /** Active (not dormant) */
    public boolean isSpawned(String name) {
        AgentContext ctx = agents.get(name);
        return ctx != null && !ctx.isDormant();
    }
    public boolean isDormant(String name) {
        AgentContext ctx = agents.get(name);
        return ctx != null && ctx.isDormant();
    }
    /** Remove a dormant agent from the map (bed was broken). */
    public void removeDormant(String name) {
        AgentContext ctx = agents.get(name);
        if (ctx != null && ctx.isDormant()) {
            agents.remove(name);
            removeAgentFromClients(ctx.getPlayer());
        }
    }
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
