package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

public class MineBlockAction implements Action {

    @Override
    public String getName() {
        return "mine_block";
    }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        JsonObject result = new JsonObject();

        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();
        BlockPos blockPos = new BlockPos(x, y, z);

        if (!(agent.level() instanceof ServerLevel level)) {
            result.addProperty("ok", false);
            result.addProperty("error", "Not in a server level");
            return result;
        }

        BlockState state = level.getBlockState(blockPos);
        if (state.isAir()) {
            result.addProperty("ok", false);
            result.addProperty("error", "Block is air");
            return result;
        }

        double distance = agent.position().distanceTo(
                new net.minecraft.world.phys.Vec3(x + 0.5, y + 0.5, z + 0.5));
        if (distance > 6.0) {
            result.addProperty("ok", false);
            result.addProperty("error", "Block too far away (distance: " + String.format("%.1f", distance) + ")");
            return result;
        }

        String blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
        level.destroyBlock(blockPos, true, agent);

        result.addProperty("ok", true);
        result.addProperty("mined_block", blockId);
        JsonObject pos = new JsonObject();
        pos.addProperty("x", x);
        pos.addProperty("y", y);
        pos.addProperty("z", z);
        result.add("position", pos);
        return result;
    }
}
