package com.pyosechang.agent.core;

import com.pyosechang.agent.AgentMod;
import com.pyosechang.agent.core.action.ActiveActionManager;
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

        FakePlayerManager manager = FakePlayerManager.getInstance();
        if (!manager.isSpawned()) return;

        FakePlayer agent = manager.getAgent();

        // Tick the active async action (movement, mining, etc.)
        ActiveActionManager.getInstance().tick(agent);

        // Auto-pickup nearby items (2-block radius)
        pickupNearbyItems(agent);
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
