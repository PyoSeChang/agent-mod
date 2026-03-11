package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;

public class SmeltAction implements Action {
    @Override
    public String getName() { return "smelt"; }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        String inputItem = params.has("input") ? params.get("input").getAsString() : null;
        ServerLevel level = (ServerLevel) agent.level();
        JsonObject result = new JsonObject();

        if (inputItem == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "input parameter required");
            return result;
        }

        // Search agent's inventory for the input item
        ItemStack inputStack = ItemStack.EMPTY;
        for (int i = 0; i < agent.getInventory().getContainerSize(); i++) {
            ItemStack stack = agent.getInventory().getItem(i);
            if (!stack.isEmpty() && ForgeRegistries.ITEMS.getKey(stack.getItem()).toString().equals(inputItem)) {
                inputStack = stack;
                break;
            }
        }

        if (inputStack.isEmpty()) {
            result.addProperty("ok", false);
            result.addProperty("error", "Item not found in inventory: " + inputItem);
            return result;
        }

        // Check smelting recipe
        SimpleContainer container = new SimpleContainer(inputStack.copy());
        Optional<SmeltingRecipe> recipe = level.getRecipeManager()
            .getRecipeFor(RecipeType.SMELTING, container, level);

        if (recipe.isEmpty()) {
            result.addProperty("ok", false);
            result.addProperty("error", "No smelting recipe for: " + inputItem);
            return result;
        }

        ItemStack output = recipe.get().getResultItem(level.registryAccess());
        result.addProperty("ok", true);
        result.addProperty("output_item", ForgeRegistries.ITEMS.getKey(output.getItem()).toString());
        result.addProperty("output_count", output.getCount());
        result.addProperty("note", "Use open_container on a furnace and click_slot to smelt. Smelting takes time.");

        return result;
    }
}
