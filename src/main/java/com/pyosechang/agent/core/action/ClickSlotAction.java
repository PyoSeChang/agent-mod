package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.registries.ForgeRegistries;

public class ClickSlotAction implements Action {
    @Override
    public String getName() { return "click_slot"; }

    @Override
    public JsonObject execute(ServerPlayer agent, JsonObject params) {
        int slotIndex = params.get("slot").getAsInt();
        String action = params.has("click_action") ? params.get("click_action").getAsString()
                : params.has("action_type") ? params.get("action_type").getAsString()
                : params.has("action") ? params.get("action").getAsString()
                : "pickup";

        ClickType clickType = switch (action) {
            case "quick_move" -> ClickType.QUICK_MOVE;
            case "throw" -> ClickType.THROW;
            case "clone" -> ClickType.CLONE;
            default -> ClickType.PICKUP;
        };

        if (agent.containerMenu == agent.inventoryMenu) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "No container open");
            return result;
        }

        // Snapshot before
        ItemStack beforeStack = ItemStack.EMPTY;
        if (slotIndex >= 0 && slotIndex < agent.containerMenu.slots.size()) {
            beforeStack = agent.containerMenu.slots.get(slotIndex).getItem().copy();
        }

        agent.containerMenu.clicked(slotIndex, 0, clickType, agent);
        agent.containerMenu.broadcastChanges();

        // Snapshot after
        ItemStack afterStack = ItemStack.EMPTY;
        if (slotIndex >= 0 && slotIndex < agent.containerMenu.slots.size()) {
            afterStack = agent.containerMenu.slots.get(slotIndex).getItem();
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("slot", slotIndex);
        result.addProperty("action", action);

        // Report what was in the slot before
        if (!beforeStack.isEmpty()) {
            result.addProperty("before_item", ForgeRegistries.ITEMS.getKey(beforeStack.getItem()).toString());
            result.addProperty("before_count", beforeStack.getCount());
        }

        // Report what is in the slot after
        if (!afterStack.isEmpty()) {
            result.addProperty("after_item", ForgeRegistries.ITEMS.getKey(afterStack.getItem()).toString());
            result.addProperty("after_count", afterStack.getCount());
        }

        // Report cursor state
        ItemStack carried = agent.containerMenu.getCarried();
        if (!carried.isEmpty()) {
            result.addProperty("carried_item", ForgeRegistries.ITEMS.getKey(carried.getItem()).toString());
            result.addProperty("carried_count", carried.getCount());
        }

        return result;
    }
}
