package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;

public class CraftAction implements Action {
    @Override
    public String getName() { return "craft"; }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        String recipeId = params.has("recipe") ? params.get("recipe").getAsString() : null;
        ServerLevel level = (ServerLevel) agent.level();
        JsonObject result = new JsonObject();

        if (recipeId == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "recipe parameter required");
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

        // For MVP: just report the recipe was found but actual crafting
        // requires container GUI interaction (open_container + click_slot)
        CraftingRecipe recipe = recipeOpt.get();
        ItemStack output = recipe.getResultItem(level.registryAccess());

        result.addProperty("ok", true);
        result.addProperty("recipe", recipeId);
        result.addProperty("output_item", ForgeRegistries.ITEMS.getKey(output.getItem()).toString());
        result.addProperty("output_count", output.getCount());
        result.addProperty("note", "Use open_container on a crafting table and click_slot to craft manually");

        return result;
    }
}
