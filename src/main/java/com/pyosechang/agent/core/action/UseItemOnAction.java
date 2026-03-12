package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import com.pyosechang.agent.core.AgentAnimation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;

/**
 * Simulates a right-click on a block face — the universal "use item on block" action.
 * Handles: hoe on dirt, seeds on farmland, bucket on water, door/lever/button, mod blocks.
 */
public class UseItemOnAction implements Action {

    @Override
    public String getName() { return "use_item_on"; }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        JsonObject result = new JsonObject();

        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();
        String faceStr = params.has("face") ? params.get("face").getAsString() : "up";

        BlockPos blockPos = new BlockPos(x, y, z);

        if (!(agent.level() instanceof ServerLevel level)) {
            result.addProperty("ok", false);
            result.addProperty("error", "Not in a server level");
            return result;
        }

        // Distance check (4.5 blocks — vanilla reach for interaction)
        double distance = agent.position().distanceTo(
            new Vec3(x + 0.5, y + 0.5, z + 0.5));
        if (distance > 4.5) {
            result.addProperty("ok", false);
            result.addProperty("error", "Block too far away (distance: " + String.format("%.1f", distance) + ", max: 4.5)");
            return result;
        }

        // Parse face direction
        Direction face = parseFace(faceStr);
        if (face == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "Invalid face: " + faceStr + ". Use: up, down, north, south, east, west");
            return result;
        }

        // Build the hit result — click location is center of the target face
        Vec3 hitLocation = Vec3.atCenterOf(blockPos).relative(face, 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitLocation, face, blockPos, false);

        // Get the item in main hand
        ItemStack mainHandItem = agent.getMainHandItem();
        InteractionHand hand = InteractionHand.MAIN_HAND;

        // Use ServerPlayerGameMode.useItemOn for player-like behavior
        ServerPlayerGameMode gameMode = agent.gameMode;
        if (gameMode == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "Agent gameMode is null — FakePlayer may not be fully initialized");
            return result;
        }

        // Look at block and swing arm
        AgentAnimation.lookAt(agent, x + 0.5, y + 0.5, z + 0.5);
        AgentAnimation.swingArm(agent);

        InteractionResult interactionResult = gameMode.useItemOn(agent, level, mainHandItem, hand, hitResult);

        result.addProperty("ok", true);
        result.addProperty("interaction_result", interactionResult.name());
        result.addProperty("item_used", mainHandItem.getItem().toString());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", x);
        pos.addProperty("y", y);
        pos.addProperty("z", z);
        pos.addProperty("face", faceStr);
        result.add("target", pos);

        return result;
    }

    private Direction parseFace(String face) {
        return switch (face.toLowerCase()) {
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }
}
