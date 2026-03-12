package com.pyosechang.agent.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

public class ClientSetup {

    public static final KeyMapping OPEN_MEMORY = new KeyMapping(
        "key.agent.memory",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_M),
        "key.categories.agent"
    );

    public static void init(FMLJavaModLoadingContext context) {
        context.getModEventBus().addListener(ClientSetup::registerKeyMappings);
        MinecraftForge.EVENT_BUS.register(new ClientTickHandler());
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MEMORY);
    }

    public static class ClientTickHandler {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // Area mark mode: intercept right-click
            if (AreaMarkHandler.isActive()) {
                // Handled in AreaMarkHandler via input event
                return;
            }

            if (OPEN_MEMORY.consumeClick()) {
                if (mc.screen instanceof MemoryListScreen) {
                    mc.setScreen(null);
                } else {
                    mc.setScreen(new MemoryListScreen());
                }
            }
        }
    }
}
