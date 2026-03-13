package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.registries.ForgeRegistries;

public class DropItemAction implements Action {

    @Override
    public String getName() {
        return "drop_item";
    }

    @Override
    public JsonObject execute(ServerPlayer agent, JsonObject params) {
        JsonObject result = new JsonObject();

        String itemIdStr = params.get("item").getAsString();
        int count = params.has("count") ? params.get("count").getAsInt() : 1;

        if (!(agent.level() instanceof ServerLevel level)) {
            result.addProperty("ok", false);
            result.addProperty("error", "Not in a server level");
            return result;
        }

        ResourceLocation itemRL = new ResourceLocation(itemIdStr);

        // Find item in inventory
        int invSlot = -1;
        for (int i = 0; i < agent.getInventory().getContainerSize(); i++) {
            ItemStack stack = agent.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                ResourceLocation stackRL = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (stackRL != null && stackRL.equals(itemRL)) {
                    invSlot = i;
                    break;
                }
            }
        }

        if (invSlot == -1) {
            result.addProperty("ok", false);
            result.addProperty("error", "Item not found in inventory: " + itemIdStr);
            return result;
        }

        ItemStack invStack = agent.getInventory().getItem(invSlot);
        int toDrop = Math.min(count, invStack.getCount());

        // Create dropped item stack
        ItemStack dropStack = invStack.copy();
        dropStack.setCount(toDrop);

        // Spawn item entity in world
        ItemEntity itemEntity = new ItemEntity(
                level,
                agent.getX(), agent.getY() + 0.5, agent.getZ(),
                dropStack
        );
        itemEntity.setPickUpDelay(40); // 2-second pickup delay
        level.addFreshEntity(itemEntity);

        // Remove from inventory
        invStack.shrink(toDrop);
        if (invStack.isEmpty()) {
            agent.getInventory().setItem(invSlot, ItemStack.EMPTY);
        }

        result.addProperty("ok", true);
        result.addProperty("dropped_item", itemIdStr);
        result.addProperty("dropped_count", toDrop);
        return result;
    }
}
