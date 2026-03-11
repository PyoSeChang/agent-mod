package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

public class ClickSlotAction implements Action {
    @Override
    public String getName() { return "click_slot"; }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        int slot = params.get("slot").getAsInt();
        String action = params.has("action") ? params.get("action").getAsString() : "pickup";

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

        agent.containerMenu.clicked(slot, 0, clickType, agent);
        agent.containerMenu.broadcastChanges();

        ItemStack carried = agent.containerMenu.getCarried();
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        if (!carried.isEmpty()) {
            result.addProperty("carried_item", ForgeRegistries.ITEMS.getKey(carried.getItem()).toString());
            result.addProperty("carried_count", carried.getCount());
        }
        return result;
    }
}
