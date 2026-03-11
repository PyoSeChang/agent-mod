package com.pyosechang.agent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.List;

public class ObservationBuilder {

    /**
     * Build a full observation JSON for the agent.
     * Overload for backward compatibility.
     */
    public static JsonObject build(FakePlayer agent) {
        return build(agent, null);
    }

    /**
     * Build a full observation JSON for the agent including world data.
     *
     * @param agent the fake player agent
     * @param level the server level (nullable for backward compat)
     */
    public static JsonObject build(FakePlayer agent, ServerLevel level) {
        JsonObject obs = new JsonObject();

        // Position
        JsonObject pos = new JsonObject();
        pos.addProperty("x", agent.getX());
        pos.addProperty("y", agent.getY());
        pos.addProperty("z", agent.getZ());
        obs.add("position", pos);

        // Inventory
        JsonArray inventory = new JsonArray();
        for (int i = 0; i < agent.getInventory().getContainerSize(); i++) {
            ItemStack stack = agent.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                JsonObject item = new JsonObject();
                item.addProperty("slot", i);
                item.addProperty("item", ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
                item.addProperty("count", stack.getCount());
                inventory.add(item);
            }
        }
        obs.add("inventory", inventory);

        // Phase 2: Nearby blocks and entities (requires level)
        if (level != null) {
            obs.add("nearby_blocks", buildNearbyBlocks(agent, level));
            obs.add("nearby_entities", buildNearbyEntities(agent, level));
        }

        return obs;
    }

    /**
     * Scan blocks within 8-block radius, return up to 100 closest non-air blocks.
     */
    private static JsonArray buildNearbyBlocks(FakePlayer agent, ServerLevel level) {
        JsonArray blocks = new JsonArray();
        BlockPos center = agent.blockPosition();
        int radius = 8;
        int maxBlocks = 100;

        // Collect all non-air blocks with distances, sorted by distance
        record BlockEntry(BlockPos pos, BlockState state, double dist) {}
        java.util.List<BlockEntry> entries = new java.util.ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos bp = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(bp);
                    if (!state.isAir()) {
                        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (dist <= radius) {
                            entries.add(new BlockEntry(bp, state, dist));
                        }
                    }
                }
            }
        }

        // Sort by distance, take closest
        entries.sort(Comparator.comparingDouble(BlockEntry::dist));
        int count = Math.min(entries.size(), maxBlocks);
        for (int i = 0; i < count; i++) {
            BlockEntry entry = entries.get(i);
            JsonObject blockObj = new JsonObject();
            JsonObject bpos = new JsonObject();
            bpos.addProperty("x", entry.pos().getX());
            bpos.addProperty("y", entry.pos().getY());
            bpos.addProperty("z", entry.pos().getZ());
            blockObj.add("pos", bpos);
            var blockKey = ForgeRegistries.BLOCKS.getKey(entry.state().getBlock());
            blockObj.addProperty("block_id", blockKey != null ? blockKey.toString() : "unknown");
            blockObj.addProperty("block_name", entry.state().getBlock().getName().getString());
            blocks.add(blockObj);
        }

        return blocks;
    }

    /**
     * Find all entities within 16-block radius of the agent.
     */
    private static JsonArray buildNearbyEntities(FakePlayer agent, ServerLevel level) {
        JsonArray entities = new JsonArray();

        List<Entity> nearby = level.getEntitiesOfClass(
                Entity.class,
                agent.getBoundingBox().inflate(16)
        );

        for (Entity entity : nearby) {
            // Exclude the agent itself
            if (entity == agent) continue;

            JsonObject entityObj = new JsonObject();
            var typeKey = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            entityObj.addProperty("type", typeKey != null ? typeKey.toString() : "unknown");

            JsonObject epos = new JsonObject();
            epos.addProperty("x", entity.getX());
            epos.addProperty("y", entity.getY());
            epos.addProperty("z", entity.getZ());
            entityObj.add("pos", epos);

            entityObj.addProperty("distance", agent.distanceTo(entity));
            entityObj.addProperty("name", entity.getDisplayName().getString());
            entityObj.addProperty("entity_id", entity.getId());

            if (entity instanceof LivingEntity living) {
                entityObj.addProperty("health", living.getHealth());
                entityObj.addProperty("max_health", living.getMaxHealth());
            }

            entities.add(entityObj);
        }

        return entities;
    }
}
