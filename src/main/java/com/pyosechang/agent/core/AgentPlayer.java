package com.pyosechang.agent.core;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.event.AgentEvent;
import com.pyosechang.agent.event.EventBus;
import com.pyosechang.agent.event.EventType;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.TickTask;
import net.minecraft.stats.Stat;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * ServerPlayer subclass for AI agents.
 *
 * Key problem: ServerPlayer.tick() does NOT call super.tick() (Player.tick()).
 * In vanilla, player physics/equipment sync run on the client (LocalPlayer).
 * Since agents have no client, we run the Player.tick() / LivingEntity.tick()
 * chain manually using accessible methods + reflection for private ones.
 */
public class AgentPlayer extends ServerPlayer {

    private static final Logger LOGGER = LogUtils.getLogger();

    // LivingEntity.detectEquipmentUpdates() is private — need reflection
    private static final Method DETECT_EQUIPMENT_UPDATES;
    static {
        Method m = null;
        try {
            m = LivingEntity.class.getDeclaredMethod("detectEquipmentUpdates");
            m.setAccessible(true);
        } catch (Exception e) {
            LOGGER.error("Failed to access LivingEntity.detectEquipmentUpdates()", e);
        }
        DETECT_EQUIPMENT_UPDATES = m;
    }

    private int lastTickedTick = -1;
    private AgentConfig config;
    private boolean hardcoreDeath = false;

    public AgentPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }

    public void setConfig(AgentConfig config) { this.config = config; }
    public AgentConfig getConfig() { return config; }
    public boolean isHardcoreDeath() { return hardcoreDeath; }

    @Override
    public void tick() {
        int currentTick = this.server.getTickCount();
        if (lastTickedTick == currentTick) return;
        lastTickedTick = currentTick;

        // 1) ServerPlayer.tick(): gameMode, wardenTracker, containerMenu, advancements
        super.tick();

        // DEBUG: track Y position changes
        double yBefore = this.getY();

        // 2) Run the chain that ServerPlayer.tick() skips:
        //    Player.tick() → LivingEntity.tick() → Entity.tick()
        //
        // We call the individual methods in order, matching vanilla's execution flow.

        // --- From Player.tick() ---
        this.noPhysics = this.isSpectator();

        // --- From LivingEntity.tick() → Entity.tick() → baseTick() ---
        this.baseTick();

        // detectEquipmentUpdates: notifies entity tracker of equipment changes
        // → makes held items, armor visible to other players
        if (DETECT_EQUIPMENT_UPDATES != null) {
            try {
                DETECT_EQUIPMENT_UPDATES.invoke(this);
            } catch (Exception e) {
                LOGGER.error("detectEquipmentUpdates failed", e);
            }
        }

        // aiStep: physics (gravity, collision), item pickup, movement
        if (!this.isRemoved()) {
            this.aiStep();
        }

        // DEBUG
        if (currentTick % 20 == 0) {
            LOGGER.info("[AgentPlayer] y={}->{} onGround={} dm={}", yBefore, this.getY(), this.onGround(), this.getDeltaMovement());
        }

        // --- From Player.tick() ---
        // Skip hunger for creative or when hunger is disabled
        if (config == null || (config.getGamemode() != AgentConfig.Gamemode.CREATIVE && config.isHungerEnabled())) {
            this.foodData.tick(this);
        }
        this.updatePlayerPose();
    }

    // --- Agent-specific overrides ---

    @Override
    public boolean isSleeping() {
        // Dormant: actually sleeping in bed
        if (this.getSleepingPos().isPresent()) return true;
        // Sleep voting: agree with real players sleeping
        if (this.level() instanceof ServerLevel sl) {
            for (var p : sl.players()) {
                if (!(p instanceof AgentPlayer) && p.isSleeping()) return true;
            }
        }
        return false;
    }

    @Override public boolean isSleepingLongEnough() { return this.isSleeping(); }
    @Override public void stopSleeping() { /* no-op — handled by AgentManager.wakeUp() */ }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        if (config == null) return true;
        if (config.getGamemode() == AgentConfig.Gamemode.CREATIVE) return true;
        if (!config.isTakeDamage()) return true;
        if (!config.isMobTargetable() && source.getEntity() != null
                && !(source.getEntity() instanceof net.minecraft.world.entity.player.Player)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean canBeSeenAsEnemy() {
        if (config != null && !config.isMobTargetable()) return false;
        return super.canBeSeenAsEnemy();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Dormant agents (sleeping in bed) are invulnerable — spawn them first to interact
        String agentName = this.getGameProfile().getName().replace("[", "").replace("]", "");
        AgentContext ctx = AgentManager.getInstance().getAgent(agentName);
        if (ctx != null && ctx.isDormant()) return false;
        return super.hurt(source, amount);
    }

    @Override
    public void die(DamageSource source) {
        if (config == null || config.getGamemode() == AgentConfig.Gamemode.CREATIVE) return;

        JsonObject data = new JsonObject();
        data.addProperty("cause", source.getMsgId());
        data.addProperty("gamemode", config.getGamemode().name());
        EventBus.getInstance().publish(AgentEvent.of(
            this.getGameProfile().getName(), EventType.AGENT_DIED, data));

        if (config.getGamemode() == AgentConfig.Gamemode.HARDCORE) {
            this.hardcoreDeath = true;
        } else {
            scheduleRespawn();
        }
    }

    private void scheduleRespawn() {
        // Immediately prevent vanilla death processing
        this.setHealth(1.0f);
        this.dead = false;
        this.deathTime = 0;

        this.server.tell(new TickTask(this.server.getTickCount() + 1, () -> {
            // Full reset
            this.setHealth(this.getMaxHealth());
            this.getFoodData().setFoodLevel(20);
            this.getFoodData().setSaturation(5.0f);
            this.clearFire();
            this.removeAllEffects();
            this.setDeltaMovement(0, 0, 0);
            this.setPose(net.minecraft.world.entity.Pose.STANDING);
            this.hurtTime = 0;

            // Respawn at bed SIDE (standing) or world spawn
            if (config.hasBed() && this.level() instanceof ServerLevel level) {
                BlockPos bedPos = new BlockPos(config.getBedX(), config.getBedY(), config.getBedZ());
                net.minecraft.world.level.block.state.BlockState bedState = level.getBlockState(bedPos);
                if (bedState.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                    net.minecraft.core.Direction facing = bedState.getValue(
                        net.minecraft.world.level.block.BedBlock.FACING);
                    var standUp = net.minecraft.world.level.block.BedBlock.findStandUpPosition(
                        net.minecraft.world.entity.EntityType.PLAYER, level, bedPos, facing, this.getYRot());
                    if (standUp.isPresent()) {
                        var pos = standUp.get();
                        this.teleportTo(pos.x, pos.y, pos.z);
                        return;
                    }
                }
                // Bed block gone or no valid stand position — stand on bed coords
                this.teleportTo(bedPos.getX() + 0.5, bedPos.getY() + 1.0, bedPos.getZ() + 0.5);
            } else {
                BlockPos spawn = this.server.overworld().getSharedSpawnPos();
                this.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            }
        }));
    }
    @Override public void awardStat(Stat<?> stat, int amount) { /* no-op */ }
    @Override public void displayClientMessage(Component message, boolean overlay) { /* no-op */ }
    @Override public void updateOptions(ServerboundClientInformationPacket packet) { /* no-op */ }
}
