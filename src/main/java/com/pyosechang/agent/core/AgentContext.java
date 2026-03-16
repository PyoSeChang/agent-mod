package com.pyosechang.agent.core;

import com.pyosechang.agent.core.action.ActiveActionManager;
import com.pyosechang.agent.monitor.InterventionQueue;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Per-agent state bundle. Each spawned agent has its own context
 * holding ServerPlayer, action manager, session state, and intervention queue.
 */
public class AgentContext {
    private final String name;
    private final ServerPlayer player;
    private final ActiveActionManager actionManager;
    private final InterventionQueue interventionQueue;

    private final PersonaConfig persona;

    // Runtime process state
    private String sessionId;
    private boolean hasLaunched = false;
    private boolean stoppedByUser = false;
    private Process runtimeProcess;

    public AgentContext(String name, ServerPlayer player, PersonaConfig persona) {
        this.name = name;
        this.player = player;
        this.persona = persona;
        this.actionManager = new ActiveActionManager(name);
        this.interventionQueue = new InterventionQueue();
        this.sessionId = UUID.randomUUID().toString();
    }

    public String getName() { return name; }
    public ServerPlayer getPlayer() { return player; }
    /** @deprecated Use {@link #getPlayer()} */
    @Deprecated
    public ServerPlayer getFakePlayer() { return player; }
    public ActiveActionManager getActionManager() { return actionManager; }
    public InterventionQueue getInterventionQueue() { return interventionQueue; }
    public PersonaConfig getPersona() { return persona; }

    public String getSessionId() { return sessionId; }
    public boolean hasLaunched() { return hasLaunched; }
    public void setHasLaunched(boolean hasLaunched) { this.hasLaunched = hasLaunched; }

    public boolean isStoppedByUser() { return stoppedByUser; }
    public void setStoppedByUser(boolean stopped) { this.stoppedByUser = stopped; }

    public Process getRuntimeProcess() { return runtimeProcess; }
    public void setRuntimeProcess(Process process) { this.runtimeProcess = process; }

    public void resetSession() {
        this.sessionId = UUID.randomUUID().toString();
        this.hasLaunched = false;
    }

    /** @return true if runtime process is currently running */
    public boolean isRuntimeRunning() {
        return runtimeProcess != null && runtimeProcess.isAlive();
    }
}
