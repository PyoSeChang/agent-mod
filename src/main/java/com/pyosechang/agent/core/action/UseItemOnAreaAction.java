package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import com.pyosechang.agent.core.AgentAnimation;
import com.pyosechang.agent.core.AgentLogger;
import com.pyosechang.agent.core.pathfinding.PathFollower;
import com.pyosechang.agent.core.pathfinding.Pathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;

import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Uses held item on every block in a 2D area (fixed Y).
 * Serpentine walk pattern to minimize travel distance.
 * Use cases: hoeing farmland, planting seeds, etc.
 */
public class UseItemOnAreaAction implements AsyncAction {

    private CompletableFuture<JsonObject> future;
    private boolean active = false;

    private List<BlockPos> positions;
    private int posIndex;
    private Direction face;

    // Sub-state
    private enum SubState { NEXT_POS, WALKING }
    private SubState subState;
    private PathFollower pathFollower;

    // Results
    private int successes;
    private int failures;
    private long startTimeMs;
    private final JsonArray failureDetails = new JsonArray();

    @Override
    public String getName() { return "use_item_on_area"; }

    @Override
    public long getTimeoutMs() {
        int posCount = positions != null ? positions.size() : 100;
        return Math.min(posCount * 3000L, 300_000L);
    }

    @Override
    public CompletableFuture<JsonObject> start(ServerPlayer agent, JsonObject params) {
        future = new CompletableFuture<>();
        active = false;

        int x1 = params.get("x1").getAsInt();
        int z1 = params.get("z1").getAsInt();
        int x2 = params.get("x2").getAsInt();
        int z2 = params.get("z2").getAsInt();
        int y = params.get("y").getAsInt();
        String faceStr = params.has("face") ? params.get("face").getAsString() : "up";

        face = parseFace(faceStr);
        if (face == null) {
            return failImmediately("Invalid face: " + faceStr);
        }

        if (!(agent.level() instanceof ServerLevel)) {
            return failImmediately("Not in a server level");
        }

        // Build serpentine pattern
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        positions = new ArrayList<>();
        boolean forward = true;
        for (int x = minX; x <= maxX; x++) {
            if (forward) {
                for (int z = minZ; z <= maxZ; z++) {
                    positions.add(new BlockPos(x, y, z));
                }
            } else {
                for (int z = maxZ; z >= minZ; z--) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
            forward = !forward;
        }

        if (positions.isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("positions_processed", 0);
            future.complete(result);
            return future;
        }

        posIndex = 0;
        successes = 0;
        failures = 0;
        failureDetails.asList().clear();
        pathFollower = new PathFollower();
        subState = SubState.NEXT_POS;
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
            case NEXT_POS -> tickNextPos(agent, level);
            case WALKING -> tickWalking(agent, level);
        }
    }

    private void tickNextPos(ServerPlayer agent, ServerLevel level) {
        if (posIndex >= positions.size()) {
            finishArea();
            return;
        }

        BlockPos target = positions.get(posIndex);
        double dist = agent.position().distanceTo(Vec3.atCenterOf(target));

        if (dist <= 4.5) {
            // Use item immediately (sync operation)
            useItemAt(agent, level, target);
            posIndex++;
            // Stay in NEXT_POS to process next position on next tick
        } else {
            // Need to walk closer
            startWalking(agent, level, target);
        }
    }

    private void startWalking(ServerPlayer agent, ServerLevel level, BlockPos target) {
        // Walk to a position near the target
        BlockPos walkTo = findStandingNear(level, target);
        if (walkTo == null) {
            AgentLogger.getInstance().logSubStep("use_item_on_area", posIndex,
                "pos=(" + target.getX() + "," + target.getY() + "," + target.getZ() + ") no standing position found",
                false, "no_standing_position");
            JsonObject detail = new JsonObject();
            JsonArray posArr = new JsonArray(); posArr.add(target.getX()); posArr.add(target.getY()); posArr.add(target.getZ());
            detail.add("pos", posArr);
            detail.addProperty("error", "no_standing_position");
            failureDetails.add(detail);
            failures++;
            posIndex++;
            return;
        }

        List<BlockPos> path = Pathfinder.findPath(level, agent.blockPosition(), walkTo, 64);
        if (path.isEmpty()) {
            AgentLogger.getInstance().logSubStep("use_item_on_area", posIndex,
                "pos=(" + target.getX() + "," + target.getY() + "," + target.getZ() + ") no path to standing pos (" + walkTo.getX() + "," + walkTo.getY() + "," + walkTo.getZ() + ")",
                false, "no_path");
            JsonObject detail = new JsonObject();
            JsonArray posArr = new JsonArray(); posArr.add(target.getX()); posArr.add(target.getY()); posArr.add(target.getZ());
            detail.add("pos", posArr);
            detail.addProperty("error", "no_path");
            failureDetails.add(detail);
            failures++;
            posIndex++;
            return;
        }

        pathFollower.start(path);
        subState = SubState.WALKING;
    }

    private void tickWalking(ServerPlayer agent, ServerLevel level) {
        BlockPos nextWp = pathFollower.getCurrentTarget();
        if (nextWp != null) {
            AgentAnimation.lookAt(agent, nextWp.getX() + 0.5, agent.getEyeY(), nextWp.getZ() + 0.5);
        }

        pathFollower.tick(agent);
        AgentAnimation.broadcast(new net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket(agent));

        if (pathFollower.isFinished()) {
            subState = SubState.NEXT_POS;
        }
    }

    private void useItemAt(ServerPlayer agent, ServerLevel level, BlockPos target) {
        AgentAnimation.lookAt(agent, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        AgentAnimation.swingArm(agent);

        Vec3 hitLocation = Vec3.atCenterOf(target).relative(face, 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitLocation, face, target, false);

        ItemStack mainHandItem = agent.getMainHandItem();
        String itemId = mainHandItem.isEmpty() ? "empty" : net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(mainHandItem.getItem()).toString();
        String blockAtTarget = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(level.getBlockState(target).getBlock()).toString();
        String aboveBlock = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(level.getBlockState(target.above()).getBlock()).toString();
        double dist = agent.position().distanceTo(Vec3.atCenterOf(target));

        if (agent.gameMode != null) {
            InteractionResult ir = agent.gameMode.useItemOn(
                agent, level, mainHandItem, InteractionHand.MAIN_HAND, hitResult);
            if (ir.consumesAction()) {
                AgentLogger.getInstance().logSubStep("use_item_on_area", posIndex,
                    "pos=(" + target.getX() + "," + target.getY() + "," + target.getZ() + ") item=" + itemId + " block=" + blockAtTarget + " above=" + aboveBlock + " dist=" + String.format("%.1f", dist) + " result=" + ir.name(),
                    true, null);
                successes++;
            } else {
                AgentLogger.getInstance().logSubStep("use_item_on_area", posIndex,
                    "pos=(" + target.getX() + "," + target.getY() + "," + target.getZ() + ") item=" + itemId + " block=" + blockAtTarget + " above=" + aboveBlock + " dist=" + String.format("%.1f", dist) + " result=" + ir.name(),
                    false, "interaction_not_consumed: " + ir.name());
                JsonObject detail = new JsonObject();
                JsonArray posArr = new JsonArray(); posArr.add(target.getX()); posArr.add(target.getY()); posArr.add(target.getZ());
                detail.add("pos", posArr);
                detail.addProperty("block", blockAtTarget);
                detail.addProperty("above", aboveBlock);
                detail.addProperty("result", ir.name());
                detail.addProperty("dist", Math.round(dist * 10.0) / 10.0);
                failureDetails.add(detail);
                failures++;
            }
        } else {
            AgentLogger.getInstance().logSubStep("use_item_on_area", posIndex,
                "pos=(" + target.getX() + "," + target.getY() + "," + target.getZ() + ")",
                false, "gameMode_null");
            JsonObject detail = new JsonObject();
            JsonArray posArr = new JsonArray(); posArr.add(target.getX()); posArr.add(target.getY()); posArr.add(target.getZ());
            detail.add("pos", posArr);
            detail.addProperty("error", "gameMode_null");
            failureDetails.add(detail);
            failures++;
        }
    }

    private void finishArea() {
        active = false;
        double seconds = (System.currentTimeMillis() - startTimeMs) / 1000.0;

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("positions_processed", successes + failures);
        result.addProperty("successes", successes);
        result.addProperty("failures", failures);
        if (!failureDetails.isEmpty()) {
            result.add("failure_details", failureDetails);
        }
        result.addProperty("seconds", Math.round(seconds * 10.0) / 10.0);
        future.complete(result);
    }

    private BlockPos findStandingNear(ServerLevel level, BlockPos target) {
        // The agent should stand near the target — try above first (for ground-level use)
        BlockPos above = target.above();
        if (level.getBlockState(above).isAir() && level.getBlockState(above.above()).isAir()) {
            return above;
        }
        // Try adjacent positions
        for (BlockPos adj : new BlockPos[]{target.north(), target.south(), target.east(), target.west()}) {
            if (level.getBlockState(adj).isAir()
                && level.getBlockState(adj.above()).isAir()
                && !level.getBlockState(adj.below()).isAir()) {
                return adj;
            }
        }
        return null;
    }

    @Override
    public void cancel() {
        if (active) {
            active = false;
            pathFollower.cancel();
            if (future != null && !future.isDone()) {
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("error", "Use item on area cancelled");
                result.addProperty("successes", successes);
                result.addProperty("failures", failures);
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
