package com.pyosechang.agent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.function.BiConsumer;

/**
 * Area mark mode: player right-clicks two blocks in the world to define area corners.
 * After two clicks, reopens MemoryEditScreen with the coordinates filled in.
 */
public class AreaMarkHandler {
    private static boolean active = false;
    private static BlockPos corner1 = null;
    private static BiConsumer<BlockPos, BlockPos> callback = null;
    private static boolean registered = false;

    public static void start(BiConsumer<BlockPos, BlockPos> onComplete) {
        active = true;
        corner1 = null;
        callback = onComplete;
        if (!registered) {
            MinecraftForge.EVENT_BUS.register(new AreaMarkHandler());
            registered = true;
        }
        // Close current screen so player can interact with world
        Minecraft.getInstance().setScreen(null);
    }

    public static boolean isActive() { return active; }

    public static void cancel() {
        active = false;
        corner1 = null;
        callback = null;
    }

    @SubscribeEvent
    public void onMouseClick(InputEvent.InteractionKeyMappingTriggered event) {
        if (!active) return;
        if (!event.isUseItem()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) mc.hitResult;
            BlockPos pos = blockHit.getBlockPos();

            event.setCanceled(true);
            event.setSwingHand(false);

            if (corner1 == null) {
                corner1 = pos;
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("§a" + net.minecraft.client.resources.language.I18n.get("gui.agent.memory.corner1", pos.toShortString())),
                        true
                    );
                }
            } else {
                BlockPos c2 = pos;
                active = false;
                if (callback != null) {
                    BiConsumer<BlockPos, BlockPos> cb = callback;
                    callback = null;
                    BlockPos finalC1 = corner1;
                    corner1 = null;
                    // Schedule on next tick to avoid input conflicts
                    mc.tell(() -> cb.accept(finalC1, c2));
                }
            }
        }
    }
}
