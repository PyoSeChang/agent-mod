package com.pyosechang.agent.core;

import com.pyosechang.agent.AgentMod;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = AgentMod.MOD_ID)
public class AgentTickHandler {

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        AgentManager manager = AgentManager.getInstance();
        if (manager.getAgentCount() == 0) return;

        for (AgentContext ctx : manager.getAllAgents()) {
            ServerPlayer agent = ctx.getPlayer();

            // Ensure agent is ticked (gravity, physics, etc.)
            // AgentPlayer has a double-tick guard, so safe even if entity system also ticks
            agent.tick();

            // Tick the active async action (movement, mining, etc.)
            ctx.getActionManager().tick(agent);

            // Sync position to clients every second (20 ticks)
            // ServerPlayer is not in PlayerList, so entity tracker may not reliably
            // broadcast position updates. This prevents client-side visual desync.
            if (agent.getServer().getTickCount() % 20 == 0) {
                AgentAnimation.broadcast(new ClientboundTeleportEntityPacket(agent));
            }

            // Auto-pickup nearby items (2-block radius)
            // Kept for Phase 1; Phase 2 will verify if ServerPlayer handles this natively
            pickupNearbyItems(agent);
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
