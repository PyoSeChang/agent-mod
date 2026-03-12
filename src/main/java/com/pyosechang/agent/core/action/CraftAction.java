package com.pyosechang.agent.core.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * Actual crafting: finds recipe, checks ingredients in inventory, consumes materials,
 * produces output. Supports count parameter for batch crafting.
 * Automatically detects 3x3 recipes that need a crafting table nearby.
 */
public class CraftAction implements Action {
    @Override
    public String getName() { return "craft"; }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        String recipeId = params.has("recipe") ? params.get("recipe").getAsString() : null;
        int count = params.has("count") ? params.get("count").getAsInt() : 1;
        ServerLevel level = (ServerLevel) agent.level();
        JsonObject result = new JsonObject();

        if (recipeId == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "recipe parameter required");
            return result;
        }

        if (count < 1 || count > 64) {
            result.addProperty("ok", false);
            result.addProperty("error", "count must be 1-64");
            return result;
        }

        // Find recipe
        ResourceLocation recipeRL = new ResourceLocation(recipeId);
        Optional<CraftingRecipe> recipeOpt = level.getRecipeManager()
            .getAllRecipesFor(RecipeType.CRAFTING).stream()
            .filter(r -> r.getId().equals(recipeRL))
            .findFirst();

        if (recipeOpt.isEmpty()) {
            result.addProperty("ok", false);
            result.addProperty("error", "Recipe not found: " + recipeId);
            return result;
        }

        CraftingRecipe recipe = recipeOpt.get();
        ItemStack output = recipe.getResultItem(level.registryAccess());

        // Check if crafting table is required (3x3)
        boolean needsTable = needsCraftingTable(recipe);
        if (needsTable && !hasCraftingTableNearby(agent, level)) {
            result.addProperty("ok", false);
            result.addProperty("error", "Recipe requires a crafting table within 4.5 blocks");
            return result;
        }

        // Collect required ingredients
        List<Ingredient> ingredients = new ArrayList<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (!ing.isEmpty()) {
                ingredients.add(ing);
            }
        }

        // For each craft iteration, verify and consume ingredients
        JsonArray craftResults = new JsonArray();
        int crafted = 0;

        for (int i = 0; i < count; i++) {
            // Find matching inventory slots for this craft
            Map<Integer, Integer> slotConsumption = matchIngredients(agent, ingredients);
            if (slotConsumption == null) {
                if (i == 0) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "Missing ingredients for " + recipeId);
                    result.addProperty("needed", ingredientList(ingredients, level));
                    return result;
                }
                break; // Partial craft — ran out of materials
            }

            // Consume ingredients
            for (Map.Entry<Integer, Integer> entry : slotConsumption.entrySet()) {
                ItemStack stack = agent.getInventory().getItem(entry.getKey());
                stack.shrink(entry.getValue());
                if (stack.isEmpty()) {
                    agent.getInventory().setItem(entry.getKey(), ItemStack.EMPTY);
                }
            }

            // Give output
            ItemStack resultStack = output.copy();
            agent.getInventory().add(resultStack);
            crafted++;
        }

        result.addProperty("ok", true);
        result.addProperty("recipe", recipeId);
        result.addProperty("output_item", ForgeRegistries.ITEMS.getKey(output.getItem()).toString());
        result.addProperty("output_per_craft", output.getCount());
        result.addProperty("times_crafted", crafted);
        result.addProperty("total_output", crafted * output.getCount());
        return result;
    }

    private boolean needsCraftingTable(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        }
        if (recipe instanceof ShapelessRecipe) {
            return recipe.getIngredients().size() > 4;
        }
        return false;
    }

    private boolean hasCraftingTableNearby(FakePlayer agent, ServerLevel level) {
        BlockPos center = agent.blockPosition();
        int range = 5; // check within 4.5 blocks
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    double dist = agent.position().distanceTo(
                        new net.minecraft.world.phys.Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
                    if (dist <= 4.5 && level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Try to match all ingredients to inventory slots.
     * Returns map of (slot -> consume count), or null if not possible.
     */
    private Map<Integer, Integer> matchIngredients(FakePlayer agent, List<Ingredient> ingredients) {
        Map<Integer, Integer> slotConsumption = new LinkedHashMap<>();

        for (Ingredient ingredient : ingredients) {
            boolean matched = false;
            for (int slot = 0; slot < agent.getInventory().getContainerSize(); slot++) {
                ItemStack stack = agent.getInventory().getItem(slot);
                if (stack.isEmpty()) continue;

                int alreadyUsed = slotConsumption.getOrDefault(slot, 0);
                if (stack.getCount() - alreadyUsed <= 0) continue;

                if (ingredient.test(stack)) {
                    slotConsumption.merge(slot, 1, Integer::sum);
                    matched = true;
                    break;
                }
            }
            if (!matched) return null;
        }

        return slotConsumption;
    }

    private String ingredientList(List<Ingredient> ingredients, ServerLevel level) {
        Set<String> items = new LinkedHashSet<>();
        for (Ingredient ing : ingredients) {
            ItemStack[] stacks = ing.getItems();
            if (stacks.length > 0) {
                ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stacks[0].getItem());
                items.add(rl != null ? rl.toString() : "unknown");
            }
        }
        return String.join(", ", items);
    }
}
