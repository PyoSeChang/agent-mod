package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

public class EquipAction implements Action {

    @Override
    public String getName() {
        return "equip";
    }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        JsonObject result = new JsonObject();

        String itemIdStr = params.get("item").getAsString();
        String slotName = params.get("slot").getAsString();

        ResourceLocation itemRL = new ResourceLocation(itemIdStr);

        // Resolve equipment slot
        EquipmentSlot equipSlot = resolveSlot(slotName);
        if (equipSlot == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "Invalid slot: " + slotName
                    + ". Valid: mainhand, offhand, head, chest, legs, feet");
            return result;
        }

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

        // Take full stack from inventory and equip it
        ItemStack invStack = agent.getInventory().getItem(invSlot);
        ItemStack equipStack = invStack.copy();
        agent.getInventory().setItem(invSlot, ItemStack.EMPTY);

        // Swap: put currently equipped item back into inventory
        ItemStack currentlyEquipped = agent.getItemBySlot(equipSlot);
        if (!currentlyEquipped.isEmpty()) {
            agent.getInventory().add(currentlyEquipped.copy());
        }

        agent.setItemSlot(equipSlot, equipStack);

        result.addProperty("ok", true);
        result.addProperty("equipped_item", itemIdStr);
        result.addProperty("slot", slotName);
        return result;
    }

    private static EquipmentSlot resolveSlot(String name) {
        return switch (name.toLowerCase()) {
            case "mainhand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default -> null;
        };
    }
}
