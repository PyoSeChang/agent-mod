package com.pyosechang.agent.core.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraftforge.common.util.FakePlayer;

import java.util.Collections;
import java.util.List;

/**
 * Tick-based path follower that smoothly moves the agent along a path.
 */
public class PathFollower {

    private static final double MOVE_SPEED = 0.215; // ~4.3 blocks/sec at 20 tps
    private static final double WAYPOINT_THRESHOLD = 0.2;

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
     * Called every server tick to move the agent toward the next waypoint.
     */
    public void tick(FakePlayer agent) {
        if (!active || currentIndex >= path.size()) {
            active = false;
            return;
        }

        BlockPos target = path.get(currentIndex);
        // Target center of block
        double tx = target.getX() + 0.5;
        double ty = target.getY();
        double tz = target.getZ() + 0.5;

        double dx = tx - agent.getX();
        double dy = ty - agent.getY();
        double dz = tz - agent.getZ();
        double distHorizontal = Math.sqrt(dx * dx + dz * dz);
        double dist3d = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist3d < WAYPOINT_THRESHOLD) {
            // Snap to waypoint and advance
            agent.setPos(tx, ty, tz);
            currentIndex++;
            if (currentIndex >= path.size()) {
                active = false;
            }
            return;
        }

        // Smoothly interpolate toward target
        if (dist3d <= MOVE_SPEED) {
            // Close enough to reach in one tick
            agent.setPos(tx, ty, tz);
        } else {
            double ratio = MOVE_SPEED / dist3d;
            double newX = agent.getX() + dx * ratio;
            double newY = agent.getY() + dy * ratio;
            double newZ = agent.getZ() + dz * ratio;
            agent.setPos(newX, newY, newZ);
        }
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
}
