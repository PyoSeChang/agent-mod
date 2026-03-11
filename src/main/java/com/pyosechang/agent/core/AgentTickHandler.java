package com.pyosechang.agent.core;

import com.pyosechang.agent.AgentMod;
import com.pyosechang.agent.core.pathfinding.PathFollower;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = AgentMod.MOD_ID)
public class AgentTickHandler {

    private static final PathFollower pathFollower = new PathFollower();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        FakePlayerManager manager = FakePlayerManager.getInstance();
        if (!manager.isSpawned()) return;

        FakePlayer agent = manager.getAgent();

        // Tick path follower
        if (!pathFollower.isFinished()) {
            pathFollower.tick(agent);
        }
    }

    /**
     * Get the shared PathFollower instance for use by actions.
     */
    public static PathFollower getPathFollower() {
        return pathFollower;
    }
}
