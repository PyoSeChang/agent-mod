package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

public class PickupItemsAction implements Action {

    @Override
    public String getName() {
        return "pickup_items";
    }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        JsonObject result = new JsonObject();

        double radius = 5.0;
        if (params.has("radius")) {
            radius = params.get("radius").getAsDouble();
        }

        if (!(agent.level() instanceof ServerLevel level)) {
            result.addProperty("ok", false);
            result.addProperty("error", "Not in a server level");
            return result;
        }

        List<ItemEntity> items = level.getEntitiesOfClass(
                ItemEntity.class,
                agent.getBoundingBox().inflate(radius)
        );

        int pickedUp = 0;
        for (ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem().copy();
            if (agent.getInventory().add(stack)) {
                itemEntity.discard();
                pickedUp++;
            } else {
                // Inventory full — if partial pickup, update the entity's stack
                if (stack.getCount() < itemEntity.getItem().getCount()) {
                    itemEntity.setItem(stack);
                    pickedUp++;
                }
                // else inventory completely full, skip
            }
        }

        result.addProperty("ok", true);
        result.addProperty("items_picked_up", pickedUp);
        return result;
    }
}
