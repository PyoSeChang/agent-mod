package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import com.pyosechang.agent.core.AgentAnimation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.registries.ForgeRegistries;

public class PlaceBlockAction implements Action {

    @Override
    public String getName() {
        return "place_block";
    }

    @Override
    public JsonObject execute(ServerPlayer agent, JsonObject params) {
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
                new Vec3(x + 0.5, y + 0.5, z + 0.5));
        if (distance > 6.0) {
            result.addProperty("ok", false);
            result.addProperty("error", "Position too far away (distance: " + String.format("%.1f", distance) + ")");
            return result;
        }

        // Target must be air or replaceable
        if (!level.getBlockState(blockPos).isAir() && !level.getBlockState(blockPos).canBeReplaced()) {
            result.addProperty("ok", false);
            result.addProperty("error", "Target position is not empty");
            return result;
        }

        // Find an adjacent solid block to click on
        AdjacentFace adjacent = findAdjacentSolid(level, blockPos);
        if (adjacent == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "No adjacent solid block to place against");
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

        // Save current mainhand, temporarily equip the block item
        int selectedSlot = agent.getInventory().selected;
        ItemStack savedMainhand = agent.getInventory().getItem(selectedSlot).copy();
        ItemStack placeItem = agent.getInventory().getItem(invSlot);

        if (invSlot == selectedSlot) {
            // Already in mainhand — just use it
        } else {
            // Swap: put block item in mainhand slot
            agent.getInventory().setItem(selectedSlot, placeItem);
            agent.getInventory().setItem(invSlot, savedMainhand);
        }

        // Build hit result and place via gameMode
        AgentAnimation.lookAt(agent, x + 0.5, y + 0.5, z + 0.5);
        AgentAnimation.swingArm(agent);

        Vec3 hitLocation = Vec3.atCenterOf(adjacent.clickOn).relative(adjacent.face, 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitLocation, adjacent.face, adjacent.clickOn, false);
        ItemStack mainHandItem = agent.getMainHandItem();

        InteractionResult ir = agent.gameMode.useItemOn(
                agent, level, mainHandItem, InteractionHand.MAIN_HAND, hitResult);

        // Restore mainhand if we swapped
        if (invSlot != selectedSlot) {
            ItemStack afterPlace = agent.getInventory().getItem(selectedSlot).copy();
            agent.getInventory().setItem(selectedSlot, agent.getInventory().getItem(invSlot).copy());
            agent.getInventory().setItem(invSlot, afterPlace);
        }

        if (ir.consumesAction()) {
            result.addProperty("ok", true);
            result.addProperty("placed_block", blockIdStr);
            JsonObject pos = new JsonObject();
            pos.addProperty("x", x);
            pos.addProperty("y", y);
            pos.addProperty("z", z);
            result.add("position", pos);
        } else {
            result.addProperty("ok", false);
            result.addProperty("error", "Block placement failed (result: " + ir.name() + ")");
        }

        return result;
    }

    private record AdjacentFace(BlockPos clickOn, Direction face) {}

    /**
     * Find an adjacent solid block to "click" on for block placement.
     * Returns the block to click and the face to click on.
     */
    private AdjacentFace findAdjacentSolid(ServerLevel level, BlockPos target) {
        // Below (most common — placing on ground)
        BlockPos below = target.below();
        if (level.getBlockState(below).isSolid()) {
            return new AdjacentFace(below, Direction.UP);
        }
        // Horizontal neighbors
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos adj = target.relative(dir);
            if (level.getBlockState(adj).isSolid()) {
                return new AdjacentFace(adj, dir.getOpposite());
            }
        }
        // Above
        BlockPos above = target.above();
        if (level.getBlockState(above).isSolid()) {
            return new AdjacentFace(above, Direction.DOWN);
        }
        return null;
    }
}
