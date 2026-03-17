package com.pyosechang.agent.core;

import com.pyosechang.agent.AgentMod;
import com.pyosechang.agent.core.memory.MemoryManager;
import com.pyosechang.agent.core.schedule.ScheduleManager;
import com.pyosechang.agent.event.AgentEvent;
import com.pyosechang.agent.event.EventBus;
import com.pyosechang.agent.event.EventType;
import com.pyosechang.agent.runtime.RuntimeManager;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Mod.EventBusSubscriber(modid = AgentMod.MOD_ID)
public class AgentTickHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Schedule tick evaluation
        MinecraftServer server = AgentManager.getInstance().getServer();
        if (server != null) {
            ServerLevel overworld = server.overworld();
            long dayTime = overworld.getDayTime();
            long dayCount = dayTime / 24000;
            long tickInDay = dayTime % 24000;
            ScheduleManager.getInstance().tick(tickInDay, dayCount, server.getTickCount());
        }

        AgentManager manager = AgentManager.getInstance();
        if (manager.getAgentCount() == 0) return;

        // Collect hardcore deaths first (can't modify collection while iterating)
        List<String> hardcoreDeaths = null;

        for (AgentContext ctx : manager.getAllAgents()) {
            // Skip dormant agents (sleeping in bed, not active)
            if (ctx.isDormant()) continue;

            ServerPlayer agent = ctx.getPlayer();

            // Check for hardcore death flag
            if (agent instanceof AgentPlayer ap && ap.isHardcoreDeath()) {
                if (hardcoreDeaths == null) hardcoreDeaths = new ArrayList<>();
                hardcoreDeaths.add(ctx.getName());
                continue; // Don't tick dead agents
            }

            // 1) Tick actions first — PathFollower sets deltaMovement for this tick
            ctx.getActionManager().tick(agent);

            // 2) Then tick agent — aiStep/travel applies deltaMovement with physics
            agent.tick();

            // Sync position to clients every second (20 ticks)
            // ServerPlayer is not in PlayerList, so entity tracker may not reliably
            // broadcast position updates. This prevents client-side visual desync.
            if (agent.getServer().getTickCount() % 20 == 0) {
                AgentAnimation.broadcast(new ClientboundTeleportEntityPacket(agent));
            }

            // Auto-pickup nearby items (2-block radius)
            pickupNearbyItems(agent);
        }

        // Process hardcore deaths outside the iteration
        if (hardcoreDeaths != null) {
            for (String name : hardcoreDeaths) {
                handleHardcoreDeath(name);
            }
        }
    }

    private static void pickupNearbyItems(ServerPlayer agent) {
        if (!(agent.level() instanceof ServerLevel level)) return;

        AABB pickupBox = agent.getBoundingBox().inflate(2.0);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, pickupBox,
            item -> item.isAlive() && !item.hasPickUpDelay());

        for (ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem();
            if (agent.getInventory().add(stack.copy())) {
                itemEntity.discard();
            }
        }
    }

    /**
     * Hardcore death: stop runtime, despawn, delete agent directory + scoped memories.
     */
    private static void handleHardcoreDeath(String name) {
        LOGGER.warn("Hardcore death for agent '{}' — permanently deleting", name);

        // 1. Stop runtime
        RuntimeManager.getInstance().stop(name);

        // 2. Despawn (removes from world + clients)
        AgentManager.getInstance().despawn(name);

        // 3. Delete agent-scoped memories
        MemoryManager.getInstance().deleteAgentMemories(name);

        // 4. Delete agent directory (persona, config, inventory)
        Path agentDir = FMLPaths.GAMEDIR.get().resolve(".agent/agents/" + name);
        if (Files.isDirectory(agentDir)) {
            try (var walk = Files.walk(agentDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException e) { LOGGER.warn("Failed to delete {}", p); }
                    });
            } catch (IOException e) {
                LOGGER.error("Failed to delete agent directory for '{}'", name, e);
            }
        }

        // 5. Publish deletion event
        EventBus.getInstance().publish(AgentEvent.of(name, EventType.AGENT_DELETED));
    }

    /**
     * When a player joins, send them agent info packets so agents appear in tab list.
     * Entity spawn is handled automatically by the entity tracker.
     */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AgentManager.getInstance().sendAgentInfoToPlayer(player);
        }
    }
}
