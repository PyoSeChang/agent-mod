package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import com.pyosechang.agent.core.AgentAnimation;
import com.pyosechang.agent.core.pathfinding.PathFollower;
import com.pyosechang.agent.core.pathfinding.Pathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Async walking movement using A* pathfinding + tick-based path following.
 * Replaces the old teleport-based MoveToAction.
 */
public class MoveToAction implements AsyncAction {

    private static final int MAX_PATH_DISTANCE = 150;
    private static final int STUCK_TIMEOUT_TICKS = 40;

    private final PathFollower pathFollower = new PathFollower();
    private CompletableFuture<JsonObject> future;
    private boolean active = false;
    private double lastX, lastY, lastZ;
    private int stuckTicks = 0;

    @Override
    public String getName() { return "move_to"; }

    @Override
    public CompletableFuture<JsonObject> start(ServerPlayer agent, JsonObject params) {
        future = new CompletableFuture<>();
        active = false;
        stuckTicks = 0;

        double x = params.get("x").getAsDouble();
        double y = params.get("y").getAsDouble();
        double z = params.get("z").getAsDouble();

        if (!(agent.level() instanceof ServerLevel level)) {
            return failImmediately("Not in a server level");
        }

        BlockPos from = agent.blockPosition();
        BlockPos to = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));

        // Already at target?
        if (from.equals(to)) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("message", "Already at target");
            result.add("position", posToJson(agent));
            future.complete(result);
            return future;
        }

        // Check distance limit
        int manhattan = Math.abs(from.getX() - to.getX())
            + Math.abs(from.getY() - to.getY())
            + Math.abs(from.getZ() - to.getZ());
        if (manhattan > MAX_PATH_DISTANCE) {
            return failImmediately("Path too long (" + manhattan + " blocks, max " + MAX_PATH_DISTANCE + ")");
        }

        // Find path
        List<BlockPos> path = Pathfinder.findPath(level, from, to, MAX_PATH_DISTANCE);
        if (path.isEmpty()) {
            return failImmediately("No path found to target");
        }

        // Start following
        pathFollower.start(path);
        active = true;
        lastX = agent.getX();
        lastY = agent.getY();
        lastZ = agent.getZ();

        return future;
    }

    @Override
    public void tick(ServerPlayer agent) {
        if (!active || future.isDone()) {
            active = false;
            return;
        }

        // Look ahead several waypoints to smooth head rotation on zigzag paths
        BlockPos lookTarget = pathFollower.getLookAheadTarget(3);
        if (lookTarget != null) {
            AgentAnimation.lookAt(agent, lookTarget.getX() + 0.5, agent.getEyeY(), lookTarget.getZ() + 0.5);
        }

        // Tick the path follower
        pathFollower.tick(agent);

        // Check if finished
        if (pathFollower.isFinished()) {
            active = false;
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.add("position", posToJson(agent));
            future.complete(result);
            return;
        }

        // Stuck detection: no movement for STUCK_TIMEOUT_TICKS
        double dx = agent.getX() - lastX;
        double dy = agent.getY() - lastY;
        double dz = agent.getZ() - lastZ;
        double moved = dx * dx + dy * dy + dz * dz;

        if (moved < 0.001) {
            stuckTicks++;
            if (stuckTicks >= STUCK_TIMEOUT_TICKS) {
                active = false;
                pathFollower.cancel();
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("error", "Agent stuck — no movement for " + STUCK_TIMEOUT_TICKS + " ticks");
                result.add("position", posToJson(agent));
                future.complete(result);
                return;
            }
        } else {
            stuckTicks = 0;
        }

        lastX = agent.getX();
        lastY = agent.getY();
        lastZ = agent.getZ();
    }

    @Override
    public void cancel() {
        if (active) {
            active = false;
            pathFollower.cancel();
            if (future != null && !future.isDone()) {
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("error", "Movement cancelled");
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

    private JsonObject posToJson(ServerPlayer agent) {
        JsonObject pos = new JsonObject();
        pos.addProperty("x", agent.getX());
        pos.addProperty("y", agent.getY());
        pos.addProperty("z", agent.getZ());
        return pos;
    }
}
