package com.pyosechang.agent;

import com.mojang.logging.LogUtils;
import com.pyosechang.agent.compat.CompatRegistry;
import com.pyosechang.agent.compat.ae2.AE2Compat;
import com.pyosechang.agent.compat.create.CreateCompat;
import com.pyosechang.agent.core.AgentMemoryPath;
import com.pyosechang.agent.core.FakePlayerManager;
import com.pyosechang.agent.core.action.*;
import com.pyosechang.agent.monitor.ChatMonitor;
import com.pyosechang.agent.monitor.TerminalIntegration;
import com.pyosechang.agent.network.AgentHttpServer;
import com.pyosechang.agent.runtime.RuntimeManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod("agent")
public class AgentMod {
    public static final String MOD_ID = "agent";
    private static final Logger LOGGER = LogUtils.getLogger();
    private final AgentHttpServer httpServer = new AgentHttpServer();

    public AgentMod() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Agent mod loaded");

        // Set server reference
        FakePlayerManager.getInstance().setServer(event.getServer());

        // Register all actions
        ActionRegistry reg = ActionRegistry.getInstance();
        reg.register(new MoveToAction());
        reg.register(new MineBlockAction());
        reg.register(new PlaceBlockAction());
        reg.register(new PickupItemsAction());
        reg.register(new DropItemAction());
        reg.register(new EquipAction());
        reg.register(new AttackAction());
        reg.register(new InteractAction());
        reg.register(new OpenContainerAction());
        reg.register(new ClickSlotAction());
        reg.register(new CloseContainerAction());
        reg.register(new CraftAction());
        reg.register(new SmeltAction());

        // Initialize memory system
        AgentMemoryPath.initialize(event.getServer());

        // Initialize monitoring
        ChatMonitor.setServer(event.getServer());
        TerminalIntegration.initialize();

        // Register mod compats (only loads if mods are present)
        CompatRegistry.getInstance().register(new AE2Compat());
        CompatRegistry.getInstance().register(new CreateCompat());

        // Start HTTP bridge server
        httpServer.start();
        RuntimeManager.getInstance().setHttpServer(httpServer);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Agent mod stopping");

        // Stop runtime if running
        RuntimeManager.getInstance().stop();

        // Stop HTTP server
        httpServer.stop();

        // Despawn agent
        FakePlayerManager.getInstance().despawn();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        // Clear server reference
        FakePlayerManager.getInstance().setServer(null);
        LOGGER.info("Agent mod stopped");
    }
}
