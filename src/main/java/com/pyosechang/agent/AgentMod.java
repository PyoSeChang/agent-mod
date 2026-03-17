package com.pyosechang.agent;

import com.mojang.logging.LogUtils;
import com.pyosechang.agent.compat.CompatRegistry;
import com.pyosechang.agent.compat.ae2.AE2Compat;
import com.pyosechang.agent.compat.create.CreateCompat;
import com.pyosechang.agent.core.AgentConfig;
import com.pyosechang.agent.core.AgentLogger;
import com.pyosechang.agent.core.AgentManager;
import com.pyosechang.agent.core.action.*;
import com.pyosechang.agent.client.ClientSetup;
import com.pyosechang.agent.core.memory.MemoryManager;
import com.pyosechang.agent.core.schedule.ManagerContext;
import com.pyosechang.agent.core.schedule.ScheduleManager;
import com.pyosechang.agent.event.ChatSubscriber;
import com.pyosechang.agent.event.EventBus;
import com.pyosechang.agent.event.LogSubscriber;
import com.pyosechang.agent.event.SSESubscriber;
import com.pyosechang.agent.network.AgentHttpServer;
import com.pyosechang.agent.runtime.ManagerRuntimeManager;
import com.pyosechang.agent.runtime.RuntimeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.loading.FMLPaths;
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
        AgentManager.getInstance().setServer(event.getServer());

        // Register all actions
        ActionRegistry reg = ActionRegistry.getInstance();

        // Async actions — fresh instance created per execution (stateful)
        reg.registerAsync(MoveToAction.class);
        reg.registerAsync(MineBlockAction.class);
        reg.registerAsync(MineAreaAction.class);
        reg.registerAsync(UseItemOnAreaAction.class);
        reg.registerAsync(SequenceAction.class);

        // Sync actions — shared instances (stateless)
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

        // Initialize EventBus subscribers
        EventBus.getInstance().subscribe(new ChatSubscriber(event.getServer()));
        EventBus.getInstance().subscribe(SSESubscriber.getInstance());
        EventBus.getInstance().subscribe(new LogSubscriber());

        // Register mod compats (only loads if mods are present)
        CompatRegistry.getInstance().register(new AE2Compat());
        CompatRegistry.getInstance().register(new CreateCompat());

        // Load memory system
        MemoryManager.getInstance().load();

        // Initialize schedule system
        ScheduleManager.getInstance().init();
        ManagerContext managerCtx = new ManagerContext();
        ScheduleManager.getInstance().setManagerContext(managerCtx);

        // Start structured logger
        AgentLogger.getInstance().startSession();

        // Start HTTP bridge server
        httpServer.start();
        RuntimeManager.getInstance().setHttpServer(httpServer);
        ManagerRuntimeManager.getInstance().setHttpServer(httpServer);

        // Spawn dormant agents (sleeping in bed) for all agents with bed config
        spawnDormantAgents(event.getServer());
    }

    private void spawnDormantAgents(MinecraftServer server) {
        java.nio.file.Path agentsDir = FMLPaths.GAMEDIR.get().resolve(".agent/agents");
        if (!java.nio.file.Files.isDirectory(agentsDir)) return;
        try (var dirs = java.nio.file.Files.list(agentsDir)) {
            dirs.filter(java.nio.file.Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                AgentConfig config = AgentConfig.load(name);
                if (config.hasBed()) {
                    AgentManager.getInstance().spawnDormant(name, server.overworld());
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to spawn dormant agents", e);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Agent mod stopping");

        // Save schedules and memory
        ScheduleManager.getInstance().save();
        MemoryManager.getInstance().save();

        // Stop manager runtime
        ManagerContext managerCtx = ScheduleManager.getInstance().getManagerContext();
        if (managerCtx != null && managerCtx.isRuntimeRunning()) {
            managerCtx.getRuntimeProcess().destroyForcibly();
            managerCtx.setRuntimeProcess(null);
        }

        // Stop structured logger
        AgentLogger.getInstance().endSession();

        // Stop all runtimes and reset sessions
        RuntimeManager.getInstance().stopAll();
        RuntimeManager.getInstance().resetAllSessions();

        // Stop HTTP server
        httpServer.stop();

        // Despawn all agents
        AgentManager.getInstance().despawnAll();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        // Clear server reference
        AgentManager.getInstance().setServer(null);
        LOGGER.info("Agent mod stopped");
    }
}
