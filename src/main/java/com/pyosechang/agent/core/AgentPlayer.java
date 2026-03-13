package com.pyosechang.agent.core;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

    public AgentPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }

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
        this.foodData.tick(this);
        this.updatePlayerPose();
    }

    // --- Agent-specific overrides ---

    @Override
    public boolean isSleeping() {
        if (this.level() instanceof ServerLevel sl) {
            for (var p : sl.players()) {
                if (!(p instanceof AgentPlayer) && p.isSleeping()) return true;
            }
        }
        return false;
    }

    @Override public boolean isSleepingLongEnough() { return this.isSleeping(); }
    @Override public void stopSleeping() { /* no-op */ }
    @Override public boolean isInvulnerableTo(DamageSource source) { return true; }
    @Override public void die(DamageSource source) { /* no-op */ }
    @Override public void awardStat(Stat<?> stat, int amount) { /* no-op */ }
    @Override public void displayClientMessage(Component message, boolean overlay) { /* no-op */ }
    @Override public void updateOptions(ServerboundClientInformationPacket packet) { /* no-op */ }
}
