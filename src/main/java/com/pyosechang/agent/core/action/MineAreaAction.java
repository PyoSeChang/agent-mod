package com.pyosechang.agent.core.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pyosechang.agent.core.AgentAnimation;
import com.pyosechang.agent.core.AgentLogger;
import com.pyosechang.agent.core.pathfinding.PathFollower;
import com.pyosechang.agent.core.pathfinding.Pathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Mines all non-air blocks in a rectangular area. Internally walks to each block
 * and mines it tick-by-tick. Skips failures and continues.
 */
public class MineAreaAction implements AsyncAction {

    private CompletableFuture<JsonObject> future;
    private boolean active = false;
    private ServerPlayer cachedAgent;

    // Block queue (top-to-bottom for natural mining order)
    private List<BlockPos> blocks;
    private int blockIndex;

    // Sub-action state
    private enum SubState { NEXT_BLOCK, WALKING, MINING }
    private SubState subState;

    // Walking sub-state
    private PathFollower pathFollower;

    // Mining sub-state
    private BlockPos miningTarget;
    private float destroyProgress;
    private int miningTick;
    private String miningBlockId;

    // Results
    private int blocksMined;
    private int blocksSkipped;
    private Map<String, Integer> itemsCollected;
    private long startTimeMs;

    @Override
    public String getName() { return "mine_area"; }

    @Override
    public long getTimeoutMs() {
        int blockCount = blocks != null ? blocks.size() : 256;
        return Math.min(blockCount * 5000L, 300_000L);
    }

    @Override
    public CompletableFuture<JsonObject> start(ServerPlayer agent, JsonObject params) {
        future = new CompletableFuture<>();
        cachedAgent = agent;
        active = false;

        int x1 = params.get("x1").getAsInt();
        int y1 = params.get("y1").getAsInt();
        int z1 = params.get("z1").getAsInt();
        int x2 = params.get("x2").getAsInt();
        int y2 = params.get("y2").getAsInt();
        int z2 = params.get("z2").getAsInt();

        if (!(agent.level() instanceof ServerLevel level)) {
            return failImmediately("Not in a server level");
        }

        // Collect non-air blocks, sorted top-to-bottom (mine from above)
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        blocks = new ArrayList<>();
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        blocks.add(pos);
                    }
                }
            }
        }

        if (blocks.isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("blocks_mined", 0);
            result.addProperty("message", "No blocks to mine in area");
            future.complete(result);
            return future;
        }

        if (blocks.size() > 256) {
            return failImmediately("Area too large: " + blocks.size() + " blocks (max 256)");
        }

        blockIndex = 0;
        blocksMined = 0;
        blocksSkipped = 0;
        itemsCollected = new LinkedHashMap<>();
        pathFollower = new PathFollower();
        subState = SubState.NEXT_BLOCK;
        startTimeMs = System.currentTimeMillis();
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

        switch (subState) {
            case NEXT_BLOCK -> tickNextBlock(agent, level);
            case WALKING -> tickWalking(agent, level);
            case MINING -> tickMining(agent, level);
        }
    }

    private void tickNextBlock(ServerPlayer agent, ServerLevel level) {
        if (blockIndex >= blocks.size()) {
            finishArea();
            return;
        }

        BlockPos target = blocks.get(blockIndex);
        BlockState state = level.getBlockState(target);

        // Skip if block became air
        if (state.isAir()) {
            blocksSkipped++;
            blockIndex++;
            return; // Will process next block on next tick
        }

        // Check distance
        double dist = agent.position().distanceTo(Vec3.atCenterOf(target));
        if (dist <= 4.5) {
            // Start mining directly
            startMining(agent, level, target, state);
        } else {
            // Need to walk closer
            startWalking(agent, level, target);
        }
    }

    private void startWalking(ServerPlayer agent, ServerLevel level, BlockPos target) {
        // Find a position adjacent to the target we can stand on
        BlockPos walkTo = findStandingPosition(level, target);
        if (walkTo == null) {
            AgentLogger.getInstance().logSubStep("mine_area", blockIndex,
                "block=(" + target.getX() + "," + target.getY() + "," + target.getZ() + ") no standing position",
                false, "no_standing_position");
            blocksSkipped++;
            blockIndex++;
            subState = SubState.NEXT_BLOCK;
            return;
        }

        List<BlockPos> path = Pathfinder.findPath(level, agent.blockPosition(), walkTo, 64);
        if (path.isEmpty()) {
            AgentLogger.getInstance().logSubStep("mine_area", blockIndex,
                "block=(" + target.getX() + "," + target.getY() + "," + target.getZ() + ") no path to (" + walkTo.getX() + "," + walkTo.getY() + "," + walkTo.getZ() + ")",
                false, "no_path");
            blocksSkipped++;
            blockIndex++;
            subState = SubState.NEXT_BLOCK;
            return;
        }

        pathFollower.start(path);
        subState = SubState.WALKING;
    }

    private void tickWalking(ServerPlayer agent, ServerLevel level) {
        // Look at next waypoint
        BlockPos nextWp = pathFollower.getCurrentTarget();
        if (nextWp != null) {
            AgentAnimation.lookAt(agent, nextWp.getX() + 0.5, nextWp.getY() + 0.5, nextWp.getZ() + 0.5);
        }

        pathFollower.tick(agent);

        if (pathFollower.isFinished()) {
            // Now try mining the current block
            BlockPos target = blocks.get(blockIndex);
            BlockState state = level.getBlockState(target);
            if (state.isAir()) {
                blocksSkipped++;
                blockIndex++;
                subState = SubState.NEXT_BLOCK;
            } else {
                startMining(agent, level, target, state);
            }
        }
    }

    private void startMining(ServerPlayer agent, ServerLevel level, BlockPos target, BlockState state) {
        destroyProgress = state.getDestroyProgress(agent, level, target);
        if (destroyProgress <= 0) {
            blocksSkipped++;
            blockIndex++;
            subState = SubState.NEXT_BLOCK;
            return;
        }

        miningTarget = target;
        miningBlockId = ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
        miningTick = 0;

        // Instant break
        if (destroyProgress >= 1.0f) {
            finishMiningBlock(agent, level);
            return;
        }

        subState = SubState.MINING;
    }

    private void tickMining(ServerPlayer agent, ServerLevel level) {
        BlockState state = level.getBlockState(miningTarget);
        if (state.isAir()) {
            blocksSkipped++;
            blockIndex++;
            subState = SubState.NEXT_BLOCK;
            return;
        }

        AgentAnimation.lookAt(agent, miningTarget.getX() + 0.5, miningTarget.getY() + 0.5, miningTarget.getZ() + 0.5);
        AgentAnimation.swingArm(agent);

        miningTick++;
        float progress = miningTick * destroyProgress;

        // Update crack animation
        int stage = Math.min((int)(progress * 10.0f), 9);
        level.destroyBlockProgress(agent.getId(), miningTarget, stage);

        if (progress >= 1.0f) {
            finishMiningBlock(agent, level);
        }
    }

    private void finishMiningBlock(ServerPlayer agent, ServerLevel level) {
        // Clear crack animation
        level.destroyBlockProgress(agent.getId(), miningTarget, -1);

        // Count items before breaking
        AABB pickupBox = new AABB(miningTarget).inflate(2.0);
        Set<Integer> existingItems = new HashSet<>();
        for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, pickupBox)) {
            existingItems.add(ie.getId());
        }

        // Break block
        if (agent.gameMode != null) {
            agent.gameMode.destroyBlock(miningTarget);
        } else {
            level.destroyBlock(miningTarget, true, agent);
        }

        // Collect dropped items
        for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, pickupBox)) {
            if (!existingItems.contains(ie.getId()) && ie.isAlive()) {
                ItemStack stack = ie.getItem();
                String itemId = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                itemsCollected.merge(itemId, stack.getCount(), Integer::sum);
                if (agent.getInventory().add(stack.copy())) {
                    ie.discard();
                }
            }
        }

        blocksMined++;
        blockIndex++;
        subState = SubState.NEXT_BLOCK;
    }

    private void finishArea() {
        active = false;
        double seconds = (System.currentTimeMillis() - startTimeMs) / 1000.0;

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("blocks_mined", blocksMined);
        result.addProperty("blocks_skipped", blocksSkipped);
        result.addProperty("seconds", Math.round(seconds * 10.0) / 10.0);

        JsonArray items = new JsonArray();
        for (Map.Entry<String, Integer> entry : itemsCollected.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("item", entry.getKey());
            item.addProperty("count", entry.getValue());
            items.add(item);
        }
        result.add("items_collected", items);

        future.complete(result);
    }

    private BlockPos findStandingPosition(ServerLevel level, BlockPos target) {
        // Try positions adjacent to target where agent can stand
        BlockPos[] candidates = {
            target.north(), target.south(), target.east(), target.west(),
            target.below(), target.above()
        };
        BlockPos best = null;
        for (BlockPos pos : candidates) {
            // Need air at pos and pos.above, solid ground at pos.below
            if (level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && !level.getBlockState(pos.below()).isAir()) {
                if (best == null) {
                    best = pos;
                }
            }
        }
        // Fallback: just go to the block's own position (for top-down mining)
        if (best == null) {
            BlockPos above = target.above();
            if (level.getBlockState(above).isAir() && level.getBlockState(above.above()).isAir()) {
                best = above;
            }
        }
        return best;
    }

    @Override
    public void cancel() {
        if (active) {
            active = false;
            pathFollower.cancel();
            if (miningTarget != null) {
                if (cachedAgent != null && cachedAgent.level() instanceof ServerLevel level) {
                    level.destroyBlockProgress(cachedAgent.getId(), miningTarget, -1);
                }
            }
            if (future != null && !future.isDone()) {
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("error", "Mine area cancelled");
                result.addProperty("blocks_mined", blocksMined);
                result.addProperty("blocks_skipped", blocksSkipped);
                future.complete(result);
            }
        }
    }

    @Override
    public boolean isActive() { return active; }

    private CompletableFuture<JsonObject> failImmediately(String error) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", error);
        future.complete(result);
        return future;
    }
}
