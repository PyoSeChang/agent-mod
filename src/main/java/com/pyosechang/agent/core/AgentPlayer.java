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
        // Skip hunger for creative mode agents
        if (config == null || config.getGamemode() != AgentConfig.Gamemode.CREATIVE) {
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
        // Creative agents are invulnerable; survival/hardcore take damage
        return config != null && config.getGamemode() == AgentConfig.Gamemode.CREATIVE;
    }

    @Override
    public void die(DamageSource source) {
        if (config == null || config.getGamemode() == AgentConfig.Gamemode.CREATIVE) return;

        // Publish death event
        JsonObject data = new JsonObject();
        data.addProperty("cause", source.getMsgId());
        data.addProperty("gamemode", config.getGamemode().name());
        EventBus.getInstance().publish(AgentEvent.of(
            this.getGameProfile().getName(), EventType.AGENT_DIED, data));

        if (config.getGamemode() == AgentConfig.Gamemode.HARDCORE) {
            // Flag for tick handler to clean up (avoid heavy I/O in death handler)
            this.hardcoreDeath = true;
        } else {
            // SURVIVAL: respawn at bed or world spawn after 1 tick
            scheduleRespawn();
        }
    }

    private void scheduleRespawn() {
        this.server.tell(new TickTask(this.server.getTickCount() + 1, () -> {
            // Reset health and food
            this.setHealth(this.getMaxHealth());
            this.getFoodData().setFoodLevel(20);
            this.getFoodData().setSaturation(5.0f);
            this.clearFire();
            this.removeAllEffects();

            // Clear death state
            this.setDeltaMovement(0, 0, 0);
            this.dead = false;
            this.deathTime = 0;

            // Teleport to bed or world spawn
            if (config.hasBed()) {
                this.teleportTo(config.getBedX() + 0.5, config.getBedY(), config.getBedZ() + 0.5);
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
