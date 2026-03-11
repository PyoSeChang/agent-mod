package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

public class PlaceBlockAction implements Action {

    @Override
    public String getName() {
        return "place_block";
    }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        JsonObject result = new JsonObject();

        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();
        String blockIdStr = params.get("block").getAsString();
        BlockPos blockPos = new BlockPos(x, y, z);

        if (!(agent.level() instanceof ServerLevel level)) {
            result.addProperty("ok", false);
            result.addProperty("error", "Not in a server level");
            return result;
        }

        // Resolve block from registry
        ResourceLocation blockRL = new ResourceLocation(blockIdStr);
        Block block = ForgeRegistries.BLOCKS.getValue(blockRL);
        if (block == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "Unknown block: " + blockIdStr);
            return result;
        }

        double distance = agent.position().distanceTo(
                new net.minecraft.world.phys.Vec3(x + 0.5, y + 0.5, z + 0.5));
        if (distance > 6.0) {
            result.addProperty("ok", false);
            result.addProperty("error", "Position too far away (distance: " + String.format("%.1f", distance) + ")");
            return result;
        }

        // Find matching item in inventory
        ResourceLocation itemRL = ForgeRegistries.ITEMS.getKey(block.asItem());
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
            result.addProperty("error", "No matching item in inventory for " + blockIdStr);
            return result;
        }

        // Place block
        BlockState blockState = block.defaultBlockState();
        level.setBlock(blockPos, blockState, 3);

        // Decrease item count
        ItemStack invStack = agent.getInventory().getItem(invSlot);
        invStack.shrink(1);
        if (invStack.isEmpty()) {
            agent.getInventory().setItem(invSlot, ItemStack.EMPTY);
        }

        result.addProperty("ok", true);
        result.addProperty("placed_block", blockIdStr);
        JsonObject pos = new JsonObject();
        pos.addProperty("x", x);
        pos.addProperty("y", y);
        pos.addProperty("z", z);
        result.add("position", pos);
        return result;
    }
}
