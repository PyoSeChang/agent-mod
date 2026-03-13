package com.pyosechang.agent.core;

import com.pyosechang.agent.AgentMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
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
            FakePlayer agent = ctx.getFakePlayer();

            // Tick the active async action (movement, mining, etc.)
            ctx.getActionManager().tick(agent);

            // Auto-pickup nearby items (2-block radius)
            pickupNearbyItems(agent);
        }
    }

    private static void pickupNearbyItems(FakePlayer agent) {
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
}
