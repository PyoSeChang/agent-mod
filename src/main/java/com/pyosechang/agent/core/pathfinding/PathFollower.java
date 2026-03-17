package com.pyosechang.agent.core.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;

/**
 * Tick-based path follower using deltaMovement (like vanilla MoveControl).
 * Sets movement velocity each tick; aiStep/travel applies it with physics.
 */
public class PathFollower {

    private static final double MOVE_SPEED = 0.215; // ~4.3 blocks/sec at 20 tps
    private static final double WAYPOINT_THRESHOLD = 0.5;
    private static final double JUMP_VELOCITY = 0.42; // vanilla jump strength

    private List<BlockPos> path = Collections.emptyList();
    private int currentIndex = 0;
    private boolean active = false;

    /**
     * Begin following a new path.
     */
    public void start(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            this.active = false;
            return;
        }
        this.path = path;
        this.currentIndex = 0;
        this.active = true;
    }

    /**
     * Called every server tick. Sets deltaMovement toward the next waypoint,
     * letting aiStep/travel handle actual movement with physics.
     */
    public void tick(ServerPlayer agent) {
        if (!active || currentIndex >= path.size()) {
            active = false;
            return;
        }

        BlockPos target = path.get(currentIndex);
        double tx = target.getX() + 0.5;
        double ty = target.getY();
        double tz = target.getZ() + 0.5;

        double dx = tx - agent.getX();
        double dy = ty - agent.getY();
        double dz = tz - agent.getZ();
        double distHorizontal = Math.sqrt(dx * dx + dz * dz);

        // Arrived at waypoint — advance to next
        if (distHorizontal < WAYPOINT_THRESHOLD && Math.abs(dy) < 1.0) {
            currentIndex++;
            if (currentIndex >= path.size()) {
                active = false;
                agent.setDeltaMovement(Vec3.ZERO);
            }
            return;
        }

        // Calculate horizontal movement direction
        double moveX = 0;
        double moveZ = 0;
        if (distHorizontal > 0.01) {
            moveX = (dx / distHorizontal) * MOVE_SPEED;
            moveZ = (dz / distHorizontal) * MOVE_SPEED;
        }

        // Jump if target is above and agent is on ground
        double moveY = agent.getDeltaMovement().y; // preserve gravity
        if (dy > 0.5 && agent.onGround()) {
            moveY = JUMP_VELOCITY;
        }

        agent.setDeltaMovement(new Vec3(moveX, moveY, moveZ));
    }

    /**
     * @return true if the agent has reached the end of the path or has no path
     */
    public boolean isFinished() {
        return !active;
    }

    /**
     * Cancel path following.
     */
    public void cancel() {
        active = false;
        path = Collections.emptyList();
        currentIndex = 0;
    }

    /**
     * @return the current path being followed
     */
    public List<BlockPos> getPath() {
        return path;
    }

    /**
     * @return the current waypoint index
     */
    public int getCurrentIndex() {
        return currentIndex;
    }

    /**
     * @return the current target waypoint, or null if not active
     */
    public BlockPos getCurrentTarget() {
        if (!active || currentIndex >= path.size()) return null;
        return path.get(currentIndex);
    }

    /**
     * @return a waypoint N steps ahead of the current index (clamped to path end), or null if not active
     */
    public BlockPos getLookAheadTarget(int lookahead) {
        if (!active || path.isEmpty()) return null;
        int idx = Math.min(currentIndex + lookahead, path.size() - 1);
        return path.get(idx);
    }
}
