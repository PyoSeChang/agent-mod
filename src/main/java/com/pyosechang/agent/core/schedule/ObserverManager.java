package com.pyosechang.agent.core.schedule;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.AgentMod;
import com.pyosechang.agent.core.memory.ScheduleMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.event.entity.living.BabyEntitySpawnEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.entity.player.BonemealEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages OBSERVER-type schedule triggers via Forge events.
 * Maintains a lookup structure: eventType → pos → list of observer registrations.
 */
@Mod.EventBusSubscriber(modid = AgentMod.MOD_ID)
public class ObserverManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ObserverManager INSTANCE = new ObserverManager();

    /** Supported event types with descriptions */
    public static final Map<String, String> SUPPORTED_EVENTS = Map.of(
        "crop_grow", "Crop growth stage change (conditions: age=N)",
        "sapling_grow", "Sapling grows into tree",
        "block_break", "Block broken by player/entity",
        "block_place", "Block placed by player/entity",
        "baby_spawn", "Baby entity spawned from breeding",
        "entity_death", "Entity dies (conditions: type=zombie)",
        "explosion", "Explosion detonates"
    );

    /**
     * Registration entry linking an observer def to its schedule.
     */
    record ObserverRegistration(String scheduleId, ObserverDef def, boolean triggered) {}

    // eventType → blockPos → list of registrations
    private final ConcurrentHashMap<String, Map<BlockPos, List<ObserverRegistration>>> registry = new ConcurrentHashMap<>();

    // scheduleId → set of triggered positions (for threshold tracking)
    private final ConcurrentHashMap<String, Set<BlockPos>> triggeredMap = new ConcurrentHashMap<>();

    private ObserverManager() {}

    public static ObserverManager getInstance() { return INSTANCE; }

    /** Register all observers for a schedule */
    public void registerSchedule(ScheduleMemory sm) {
        ScheduleConfig config = sm.getConfig();
        if (config.getType() != ScheduleConfig.Type.OBSERVER) return;

        String scheduleId = sm.getId();
        triggeredMap.putIfAbsent(scheduleId, ConcurrentHashMap.newKeySet());

        for (ObserverDef def : config.getObservers()) {
            if (def.getEventType() == null || def.getEventType().isEmpty()) {
                LOGGER.warn("Skipping observer with null/empty eventType in schedule '{}'", scheduleId);
                continue;
            }
            registry.computeIfAbsent(def.getEventType(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(def.getBlockPos(), k -> new ArrayList<>())
                .add(new ObserverRegistration(scheduleId, def, false));
        }
        LOGGER.info("Registered {} observers for schedule '{}'", config.getObservers().size(), scheduleId);
    }

    /** Unregister all observers for a schedule */
    public void unregisterSchedule(String scheduleId) {
        triggeredMap.remove(scheduleId);
        for (Map<BlockPos, List<ObserverRegistration>> posMap : registry.values()) {
            for (var regList : posMap.values()) {
                regList.removeIf(r -> r.scheduleId().equals(scheduleId));
            }
            // Clean up empty pos entries
            posMap.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        // Clean up empty event entries
        registry.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /** Add observers to an existing schedule */
    public void addObservers(String scheduleId, List<ObserverDef> newObservers) {
        triggeredMap.putIfAbsent(scheduleId, ConcurrentHashMap.newKeySet());
        for (ObserverDef def : newObservers) {
            registry.computeIfAbsent(def.getEventType(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(def.getBlockPos(), k -> new ArrayList<>())
                .add(new ObserverRegistration(scheduleId, def, false));
        }
    }

    /** Remove observers at specific positions from a schedule */
    public void removeObservers(String scheduleId, List<BlockPos> positions) {
        Set<BlockPos> posSet = new HashSet<>(positions);
        for (Map<BlockPos, List<ObserverRegistration>> posMap : registry.values()) {
            for (BlockPos pos : posSet) {
                if (posMap.containsKey(pos)) {
                    posMap.get(pos).removeIf(r -> r.scheduleId().equals(scheduleId));
                    if (posMap.get(pos).isEmpty()) posMap.remove(pos);
                }
            }
        }
        // Remove from triggered set
        Set<BlockPos> triggered = triggeredMap.get(scheduleId);
        if (triggered != null) {
            triggered.removeAll(posSet);
        }
    }

    /** Get observer states for a schedule */
    public JsonObject getStates(String scheduleId) {
        Set<BlockPos> triggered = triggeredMap.getOrDefault(scheduleId, Set.of());
        ScheduleMemory sm = ScheduleManager.getInstance().get(scheduleId);

        JsonObject result = new JsonObject();
        JsonArray observersArr = new JsonArray();

        if (sm != null && sm.getConfig().getType() == ScheduleConfig.Type.OBSERVER) {
            for (ObserverDef def : sm.getConfig().getObservers()) {
                JsonObject obs = def.toJson();
                obs.addProperty("triggered", triggered.contains(def.getBlockPos()));
                observersArr.add(obs);
            }
        }

        result.add("observers", observersArr);
        result.addProperty("triggered_count", triggered.size());
        result.addProperty("threshold", sm != null ? sm.getConfig().getThreshold() : 0);
        return result;
    }

    /** Get total observer count for a schedule */
    public int getObserverCount(String scheduleId) {
        ScheduleMemory sm = ScheduleManager.getInstance().get(scheduleId);
        return sm != null ? sm.getConfig().getObservers().size() : 0;
    }

    // --- Event processing ---

    private void processEvent(String eventType, BlockPos pos, BlockState state) {
        Map<BlockPos, List<ObserverRegistration>> posMap = registry.get(eventType);
        if (posMap == null) {
            LOGGER.debug("processEvent: no registry for eventType '{}'", eventType);
            return;
        }

        List<ObserverRegistration> regs = posMap.get(pos);
        if (regs == null || regs.isEmpty()) {
            LOGGER.debug("processEvent: no regs at pos {} for '{}'", pos, eventType);
            return;
        }

        for (ObserverRegistration reg : regs) {
            if (!matchesCondition(reg.def(), state)) {
                LOGGER.debug("processEvent: condition mismatch at {} (condition='{}', state={})",
                    pos, reg.def().getCondition(), state);
                continue;
            }
            LOGGER.info("processEvent: MATCH at {} for schedule '{}' (condition='{}')",
                pos, reg.scheduleId(), reg.def().getCondition());

            Set<BlockPos> triggered = triggeredMap.computeIfAbsent(reg.scheduleId(), k -> ConcurrentHashMap.newKeySet());
            triggered.add(pos);

            // Check threshold
            ScheduleMemory se = ScheduleManager.getInstance().get(reg.scheduleId());
            if (se != null) {
                int threshold = se.getConfig().getThreshold();
                if (triggered.size() >= threshold) {
                    LOGGER.info("Observer threshold reached for schedule '{}' ({}/{})",
                        reg.scheduleId(), triggered.size(), threshold);
                    triggered.clear(); // Reset for next cycle
                    ScheduleManager.getInstance().onObserverTriggered(reg.scheduleId());
                }
            }
        }
    }

    private void processEntityEvent(String eventType, BlockPos pos, String entityType) {
        Map<BlockPos, List<ObserverRegistration>> posMap = registry.get(eventType);
        if (posMap == null) return;

        // For entity events, check all registered positions within 3 blocks
        for (var entry : posMap.entrySet()) {
            BlockPos regPos = entry.getKey();
            if (regPos.distSqr(pos) > 9) continue; // 3 block radius

            for (ObserverRegistration reg : entry.getValue()) {
                if (reg.def().getCondition() != null && !reg.def().getCondition().isEmpty()) {
                    if (reg.def().getCondition().startsWith("type=")) {
                        String expected = reg.def().getCondition().substring("type=".length());
                        if (!entityType.contains(expected)) continue;
                    }
                }

                Set<BlockPos> triggered = triggeredMap.computeIfAbsent(reg.scheduleId(), k -> ConcurrentHashMap.newKeySet());
                triggered.add(pos);

                ScheduleMemory se = ScheduleManager.getInstance().get(reg.scheduleId());
                if (se != null && triggered.size() >= se.getConfig().getThreshold()) {
                    triggered.clear();
                    ScheduleManager.getInstance().onObserverTriggered(reg.scheduleId());
                }
            }
        }
    }

    private boolean matchesCondition(ObserverDef def, BlockState state) {
        if (def.getCondition() == null || def.getCondition().isEmpty()) return true;
        if (state == null) return true;

        // Parse condition like "age=7"
        String[] parts = def.getCondition().split("=", 2);
        if (parts.length != 2) return true;

        String propName = parts[0].trim();
        String expected = parts[1].trim();

        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equals(propName)) {
                String actual = state.getValue(prop).toString();
                return actual.equals(expected);
            }
        }
        return false; // property not found
    }

    // --- Forge Event Handlers ---

    @SubscribeEvent
    public static void onCropGrow(BlockEvent.CropGrowEvent.Post event) {
        BlockPos pos = event.getPos();
        // event.getState() returns the OLD state (before growth)
        // Read the CURRENT state from the world to get the new age
        BlockState state = event.getLevel().getBlockState(pos);
        String blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock()) != null
            ? ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString() : "";

        LOGGER.info("CropGrowEvent.Post at {} newState={}", pos, state);

        // Saplings are a subset of CropGrowEvent
        if (blockId.contains("sapling")) {
            INSTANCE.processEvent("sapling_grow", pos, state);
        } else {
            INSTANCE.processEvent("crop_grow", pos, state);
        }
    }

    @SubscribeEvent
    public static void onBonemeal(BonemealEvent event) {
        // Bonemeal doesn't fire CropGrowEvent — handle it here
        // Check the block state AFTER bonemeal is applied (next tick)
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            BlockPos pos = event.getPos();
            serverLevel.getServer().execute(() -> {
                BlockState newState = serverLevel.getBlockState(pos);
                INSTANCE.processEvent("crop_grow", pos, newState);
            });
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        INSTANCE.processEvent("block_break", event.getPos(), event.getState());
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        INSTANCE.processEvent("block_place", event.getPos(), event.getPlacedBlock());
    }

    @SubscribeEvent
    public static void onBabySpawn(BabyEntitySpawnEvent event) {
        if (event.getChild() != null) {
            BlockPos pos = event.getChild().blockPosition();
            String type = ForgeRegistries.ENTITY_TYPES.getKey(event.getChild().getType()) != null
                ? ForgeRegistries.ENTITY_TYPES.getKey(event.getChild().getType()).toString() : "";
            INSTANCE.processEntityEvent("baby_spawn", pos, type);
        }
    }

    @SubscribeEvent
    public static void onEntityDeath(LivingDeathEvent event) {
        BlockPos pos = event.getEntity().blockPosition();
        String type = ForgeRegistries.ENTITY_TYPES.getKey(event.getEntity().getType()) != null
            ? ForgeRegistries.ENTITY_TYPES.getKey(event.getEntity().getType()).toString() : "";
        INSTANCE.processEntityEvent("entity_death", pos, type);
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!event.getAffectedBlocks().isEmpty()) {
            BlockPos center = new BlockPos((int) event.getExplosion().getPosition().x,
                (int) event.getExplosion().getPosition().y, (int) event.getExplosion().getPosition().z);
            INSTANCE.processEvent("explosion", center, null);
        }
    }
}
