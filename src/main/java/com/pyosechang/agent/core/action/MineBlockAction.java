package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import com.pyosechang.agent.core.AgentAnimation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.concurrent.CompletableFuture;

/**
 * Tick-based block mining with proper destroy progress, crack animation,
 * and tool-dependent mining speed.
 */
public class MineBlockAction implements AsyncAction {

    private CompletableFuture<JsonObject> future;
    private boolean active = false;
    private BlockPos targetPos;
    private float destroyProgress; // per-tick progress
    private int currentTick;
    private int totalTicks;
    private String blockId;

    @Override
    public String getName() { return "mine_block"; }

    @Override
    public CompletableFuture<JsonObject> start(FakePlayer agent, JsonObject params) {
        future = new CompletableFuture<>();
        active = false;
        currentTick = 0;

        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();
        targetPos = new BlockPos(x, y, z);

        if (!(agent.level() instanceof ServerLevel level)) {
            return failImmediately("Not in a server level");
        }

        BlockState state = level.getBlockState(targetPos);
        if (state.isAir()) {
            return failImmediately("Block is air");
        }

        // Distance check
        double distance = agent.position().distanceTo(
            new net.minecraft.world.phys.Vec3(x + 0.5, y + 0.5, z + 0.5));
        if (distance > 6.0) {
            return failImmediately("Block too far away (distance: " + String.format("%.1f", distance) + ")");
        }

        // Calculate per-tick destroy progress (accounts for tool + enchantments)
        destroyProgress = state.getDestroyProgress(agent, level, targetPos);
        if (destroyProgress <= 0) {
            return failImmediately("Block is unbreakable");
        }

        blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();

        // Instant break (creative-like speed or very soft block with good tool)
        if (destroyProgress >= 1.0f) {
            finishMining(agent, level, 1);
            return future;
        }

        totalTicks = (int) Math.ceil(1.0f / destroyProgress);
        active = true;
        return future;
    }

    @Override
    public void tick(FakePlayer agent) {
        if (!active || future.isDone()) {
            active = false;
            return;
        }

        if (!(agent.level() instanceof ServerLevel level)) {
            cancel();
            return;
        }

        // Verify block hasn't changed (e.g. broken by someone else)
        BlockState state = level.getBlockState(targetPos);
        if (state.isAir()) {
            active = false;
            cleanupAnimation(level);
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "Block disappeared during mining");
            future.complete(result);
            return;
        }

        // Look at block and swing arm each tick
        AgentAnimation.lookAt(agent, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
        AgentAnimation.swingArm(agent);

        currentTick++;
        float progress = currentTick * destroyProgress;

        // Update crack animation (0-9 stages, visible to all players)
        int stage = (int) (progress * 10.0f);
        if (stage > 9) stage = 9;
        level.destroyBlockProgress(agent.getId(), targetPos, stage);

        // Check if mining is complete
        if (progress >= 1.0f) {
            finishMining(agent, level, currentTick);
        }
    }

    @Override
    public void cancel() {
        if (active) {
            active = false;
            if (targetPos != null) {
                FakePlayer agent = com.pyosechang.agent.core.FakePlayerManager.getInstance().getAgent();
                if (agent != null && agent.level() instanceof ServerLevel level) {
                    cleanupAnimation(level);
                }
            }
            if (future != null && !future.isDone()) {
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("error", "Mining cancelled");
                future.complete(result);
            }
        }
    }

    @Override
    public boolean isActive() { return active; }

    private void finishMining(FakePlayer agent, ServerLevel level, int ticksUsed) {
        active = false;
        // Reset crack animation
        cleanupAnimation(level);
        // Use gameMode.destroyBlock for proper drops, tool durability, and events
        if (agent.gameMode != null) {
            agent.gameMode.destroyBlock(targetPos);
        } else {
            // Fallback: direct destroy with drops
            level.destroyBlock(targetPos, true, agent);
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("mined_block", blockId);
        result.addProperty("ticks", ticksUsed);
        result.addProperty("seconds", String.format("%.2f", ticksUsed / 20.0));
        JsonObject pos = new JsonObject();
        pos.addProperty("x", targetPos.getX());
        pos.addProperty("y", targetPos.getY());
        pos.addProperty("z", targetPos.getZ());
        result.add("position", pos);
        future.complete(result);
    }

    private void cleanupAnimation(ServerLevel level) {
        // -1 clears the break animation
        level.destroyBlockProgress(
            com.pyosechang.agent.core.FakePlayerManager.getInstance().getAgent() != null
                ? com.pyosechang.agent.core.FakePlayerManager.getInstance().getAgent().getId()
                : 0,
            targetPos, -1);
    }

    private CompletableFuture<JsonObject> failImmediately(String error) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", error);
        future.complete(result);
        return future;
    }
}
