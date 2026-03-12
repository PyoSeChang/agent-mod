package com.pyosechang.agent;

import com.mojang.logging.LogUtils;
import com.pyosechang.agent.compat.CompatRegistry;
import com.pyosechang.agent.compat.ae2.AE2Compat;
import com.pyosechang.agent.compat.create.CreateCompat;
import com.pyosechang.agent.core.AgentLogger;
import com.pyosechang.agent.core.FakePlayerManager;
import com.pyosechang.agent.core.action.*;
import com.pyosechang.agent.client.ClientSetup;
import com.pyosechang.agent.core.memory.MemoryManager;
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
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod("agent")
public class AgentMod {
    public static final String MOD_ID = "agent";
    private static final Logger LOGGER = LogUtils.getLogger();
    private final AgentHttpServer httpServer = new AgentHttpServer();

    public AgentMod() {
        var context = FMLJavaModLoadingContext.get();
        var modBus = context.getModEventBus();
        MinecraftForge.EVENT_BUS.register(this);

        if (FMLEnvironment.dist.isClient()) {
            ClientSetup.init(context);
        }
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

        reg.register(new DropItemAction());
        reg.register(new EquipAction());
        reg.register(new AttackAction());
        reg.register(new InteractAction());
        reg.register(new OpenContainerAction());
        reg.register(new ClickSlotAction());
        reg.register(new ContainerTransferAction());
        reg.register(new CloseContainerAction());
        reg.register(new CraftAction());
        reg.register(new SmeltAction());
        reg.register(new UseItemOnAction());
        reg.register(new MineAreaAction());
        reg.register(new UseItemOnAreaAction());
        reg.register(new SequenceAction());

        // Initialize monitoring
        ChatMonitor.setServer(event.getServer());
        TerminalIntegration.initialize();

        // Register mod compats (only loads if mods are present)
        CompatRegistry.getInstance().register(new AE2Compat());
        CompatRegistry.getInstance().register(new CreateCompat());

        // Load memory system
        MemoryManager.getInstance().load();

        // Start structured logger
        AgentLogger.getInstance().startSession();

        // Start HTTP bridge server
        httpServer.start();
        RuntimeManager.getInstance().setHttpServer(httpServer);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Agent mod stopping");

        // Save memory system
        MemoryManager.getInstance().save();

        // Stop structured logger
        AgentLogger.getInstance().endSession();

        // Stop runtime if running and reset session
        RuntimeManager.getInstance().stop();
        RuntimeManager.getInstance().resetSession();

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
