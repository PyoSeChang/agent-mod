package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;

public class OpenContainerAction implements Action {
    @Override
    public String getName() { return "open_container"; }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();

        ServerLevel level = (ServerLevel) agent.level();
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);

        // Simulate right-click on block to open container
        BlockHitResult hit = new BlockHitResult(
            Vec3.atCenterOf(pos), Direction.UP, pos, false);
        InteractionResult result = state.use(level, agent, InteractionHand.MAIN_HAND, hit);

        JsonObject response = new JsonObject();
        response.addProperty("ok", result.consumesAction());
        response.addProperty("block", state.getBlock().getDescriptionId());

        // If agent has an open container menu, report its contents
        if (agent.containerMenu != agent.inventoryMenu) {
            response.addProperty("container_type", agent.containerMenu.getClass().getSimpleName());
            response.addProperty("slot_count", agent.containerMenu.slots.size());
        }

        return response;
    }
}
