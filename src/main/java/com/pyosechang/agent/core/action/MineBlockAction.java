package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import com.pyosechang.agent.core.AgentAnimation;
import com.pyosechang.agent.core.pathfinding.PathFollower;
import com.pyosechang.agent.core.pathfinding.Pathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Tick-based block mining with proper destroy progress, crack animation,
 * and tool-dependent mining speed.
 */
public class MineBlockAction implements AsyncAction {

    private enum State { MINING, COLLECTING }

    private CompletableFuture<JsonObject> future;
    private boolean active = false;
    private State state;
    private ServerPlayer cachedAgent;
    private BlockPos targetPos;
    private float destroyProgress; // per-tick progress
    private int currentTick;
    private int totalTicks;
    private String blockId;

    // Collecting state: walk to mined block position to pick up drops
    private PathFollower pathFollower;
    private JsonObject pendingResult; // result built during mining, completed after collecting

    @Override
    public String getName() { return "mine_block"; }

    @Override
    public CompletableFuture<JsonObject> start(ServerPlayer agent, JsonObject params) {
        future = new CompletableFuture<>();
        active = false;
        state = State.MINING;
        cachedAgent = agent;
        currentTick = 0;
        pathFollower = new PathFollower();

        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();
        targetPos = new BlockPos(x, y, z);

        if (!(agent.level() instanceof ServerLevel level)) {
            return failImmediately("Not in a server level");
        }

        BlockState blockState = level.getBlockState(targetPos);
        if (blockState.isAir()) {
            return failImmediately("Block is air");
        }

        // Distance check
        double distance = agent.position().distanceTo(
            new net.minecraft.world.phys.Vec3(x + 0.5, y + 0.5, z + 0.5));
        if (distance > 6.0) {
            return failImmediately("Block too far away (distance: " + String.format("%.1f", distance) + ")");
        }

        // Calculate per-tick destroy progress (accounts for tool + enchantments)
        destroyProgress = blockState.getDestroyProgress(agent, level, targetPos);
        if (destroyProgress <= 0) {
            return failImmediately("Block is unbreakable");
        }

        blockId = ForgeRegistries.BLOCKS.getKey(blockState.getBlock()).toString();

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
    public void tick(ServerPlayer agent) {
        if (!active || future.isDone()) {
            active = false;
            return;
        }

        if (!(agent.level() instanceof ServerLevel level)) {
            cancel();
            return;
        }

        switch (state) {
            case MINING -> tickMining(agent, level);
            case COLLECTING -> tickCollecting(agent);
        }
    }

    private void tickMining(ServerPlayer agent, ServerLevel level) {
        // Verify block hasn't changed (e.g. broken by someone else)
        BlockState blockState = level.getBlockState(targetPos);
        if (blockState.isAir()) {
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

    private void tickCollecting(ServerPlayer agent) {
        BlockPos nextWp = pathFollower.getCurrentTarget();
        if (nextWp != null) {
            AgentAnimation.lookAt(agent, nextWp.getX() + 0.5, nextWp.getY() + 0.5, nextWp.getZ() + 0.5);
        }

        pathFollower.tick(agent);

        if (pathFollower.isFinished()) {
            // Arrived at mined block position — tick handler will auto-pickup items
            active = false;
            future.complete(pendingResult);
        }
    }

    @Override
    public void cancel() {
        if (active) {
            active = false;
            if (pathFollower != null) pathFollower.cancel();
            if (targetPos != null) {
                if (cachedAgent != null && cachedAgent.level() instanceof ServerLevel level) {
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

    private void finishMining(ServerPlayer agent, ServerLevel level, int ticksUsed) {
        // Reset crack animation
        cleanupAnimation(level);

        // Use gameMode.destroyBlock for proper drops, tool durability, and events
        if (agent.gameMode != null) {
            agent.gameMode.destroyBlock(targetPos);
        } else {
            // Fallback: direct destroy with drops
            level.destroyBlock(targetPos, true, agent);
        }

        // Build result (will be completed after item collection)
        pendingResult = new JsonObject();
        pendingResult.addProperty("ok", true);
        pendingResult.addProperty("mined_block", blockId);
        pendingResult.addProperty("ticks", ticksUsed);
        pendingResult.addProperty("seconds", String.format("%.2f", ticksUsed / 20.0));
        JsonObject pos = new JsonObject();
        pos.addProperty("x", targetPos.getX());
        pos.addProperty("y", targetPos.getY());
        pos.addProperty("z", targetPos.getZ());
        pendingResult.add("position", pos);

        // Check if agent is already close enough for tick handler to pick up items
        double dist = agent.position().distanceTo(
            new net.minecraft.world.phys.Vec3(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5));
        if (dist <= 2.0) {
            // Already in pickup range — complete immediately
            active = false;
            future.complete(pendingResult);
            return;
        }

        // Walk to the mined block position to collect drops
        List<BlockPos> path = Pathfinder.findPath(level, agent.blockPosition(), targetPos, 32);
        if (path.isEmpty()) {
            // Can't path there — complete anyway, tick handler may eventually pick up
            active = false;
            future.complete(pendingResult);
            return;
        }

        pathFollower.start(path);
        state = State.COLLECTING;
    }

    private void cleanupAnimation(ServerLevel level) {
        // -1 clears the break animation
        level.destroyBlockProgress(
            cachedAgent != null ? cachedAgent.getId() : 0,
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
