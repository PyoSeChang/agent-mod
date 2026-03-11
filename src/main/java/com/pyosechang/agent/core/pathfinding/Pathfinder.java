package com.pyosechang.agent.core.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Simple A* pathfinder for the fake player agent.
 */
public class Pathfinder {

    private static final int MAX_SEARCH_NODES = 1000;

    /**
     * Find a path from one position to another using A*.
     *
     * @param level       the server level
     * @param from        starting position
     * @param to          target position
     * @param maxDistance  maximum search distance (Manhattan)
     * @return ordered list of BlockPos waypoints, or empty list if no path found
     */
    public static List<BlockPos> findPath(ServerLevel level, BlockPos from, BlockPos to, int maxDistance) {
        if (from.equals(to)) {
            return Collections.singletonList(to);
        }

        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingInt(n -> n.fCost));
        Map<BlockPos, Node> allNodes = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();

        Node startNode = new Node(from, null, 0, manhattan(from, to));
        openSet.add(startNode);
        allNodes.put(from, startNode);

        int nodesExplored = 0;

        while (!openSet.isEmpty() && nodesExplored < MAX_SEARCH_NODES) {
            Node current = openSet.poll();
            nodesExplored++;

            if (current.pos.equals(to)) {
                return reconstructPath(current);
            }

            closedSet.add(current.pos);

            for (BlockPos neighborPos : getNeighbors(level, current.pos)) {
                if (closedSet.contains(neighborPos)) continue;
                if (manhattan(neighborPos, from) > maxDistance) continue;

                int tentativeG = current.gCost + 1;
                Node existing = allNodes.get(neighborPos);

                if (existing == null) {
                    Node neighbor = new Node(neighborPos, current, tentativeG, tentativeG + manhattan(neighborPos, to));
                    allNodes.put(neighborPos, neighbor);
                    openSet.add(neighbor);
                } else if (tentativeG < existing.gCost) {
                    openSet.remove(existing);
                    existing.parent = current;
                    existing.gCost = tentativeG;
                    existing.fCost = tentativeG + manhattan(neighborPos, to);
                    openSet.add(existing);
                }
            }
        }

        return Collections.emptyList();
    }

    /**
     * Get walkable neighbor positions including same-level, step-up, and step-down.
     */
    private static List<BlockPos> getNeighbors(ServerLevel level, BlockPos pos) {
        List<BlockPos> neighbors = new ArrayList<>();
        int[][] horizontalDirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int[] dir : horizontalDirs) {
            int nx = pos.getX() + dir[0];
            int nz = pos.getZ() + dir[1];

            // Same level: walkable at same Y
            BlockPos sameLevel = new BlockPos(nx, pos.getY(), nz);
            if (isWalkable(level, sameLevel)) {
                neighbors.add(sameLevel);
                continue;
            }

            // Step up 1 block: need 3 blocks clearance above current pos
            BlockPos stepUp = new BlockPos(nx, pos.getY() + 1, nz);
            if (isWalkable(level, stepUp) && isPassable(level, pos.above(2))) {
                neighbors.add(stepUp);
                continue;
            }

            // Step down 1 block
            BlockPos stepDown1 = new BlockPos(nx, pos.getY() - 1, nz);
            if (isWalkable(level, stepDown1)) {
                neighbors.add(stepDown1);
                continue;
            }

            // Step down 2 blocks (short fall)
            BlockPos stepDown2 = new BlockPos(nx, pos.getY() - 2, nz);
            if (isWalkable(level, stepDown2) && isPassable(level, new BlockPos(nx, pos.getY() - 1, nz))) {
                neighbors.add(stepDown2);
                continue;
            }

            // Step down 3 blocks (max safe fall)
            BlockPos stepDown3 = new BlockPos(nx, pos.getY() - 3, nz);
            if (isWalkable(level, stepDown3)
                    && isPassable(level, new BlockPos(nx, pos.getY() - 1, nz))
                    && isPassable(level, new BlockPos(nx, pos.getY() - 2, nz))) {
                neighbors.add(stepDown3);
            }
        }

        return neighbors;
    }

    /**
     * A position is walkable if the feet and head blocks are passable,
     * and the block below is solid (standing surface).
     */
    private static boolean isWalkable(ServerLevel level, BlockPos feetPos) {
        return isPassable(level, feetPos)
                && isPassable(level, feetPos.above())
                && level.getBlockState(feetPos.below()).isSolid();
    }

    /**
     * A block is passable if it is air or not solid (e.g., flowers, tall grass).
     */
    private static boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || !state.isSolid();
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX())
                + Math.abs(a.getY() - b.getY())
                + Math.abs(a.getZ() - b.getZ());
    }

    private static List<BlockPos> reconstructPath(Node node) {
        LinkedList<BlockPos> path = new LinkedList<>();
        while (node != null) {
            path.addFirst(node.pos);
            node = node.parent;
        }
        return path;
    }

    private static class Node {
        final BlockPos pos;
        Node parent;
        int gCost;
        int fCost;

        Node(BlockPos pos, Node parent, int gCost, int fCost) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.fCost = fCost;
        }
    }
}
