package com.pyosechang.agent.core;

import com.pyosechang.agent.core.action.ActiveActionManager;
import com.pyosechang.agent.monitor.InterventionQueue;
import net.minecraftforge.common.util.FakePlayer;

import java.util.UUID;

/**
 * Per-agent state bundle. Each spawned agent has its own context
 * holding FakePlayer, action manager, session state, and intervention queue.
 */
public class AgentContext {
    private final String name;
    private final FakePlayer fakePlayer;
    private final ActiveActionManager actionManager;
    private final InterventionQueue interventionQueue;

    private final PersonaConfig persona;

    // Runtime process state
    private String sessionId;
    private boolean hasLaunched = false;
    private Process runtimeProcess;

    public AgentContext(String name, FakePlayer fakePlayer, PersonaConfig persona) {
        this.name = name;
        this.fakePlayer = fakePlayer;
        this.persona = persona;
        this.actionManager = new ActiveActionManager();
        this.interventionQueue = new InterventionQueue();
        this.sessionId = UUID.randomUUID().toString();
    }

    public String getName() { return name; }
    public FakePlayer getFakePlayer() { return fakePlayer; }
    public ActiveActionManager getActionManager() { return actionManager; }
    public InterventionQueue getInterventionQueue() { return interventionQueue; }
    public PersonaConfig getPersona() { return persona; }

    public String getSessionId() { return sessionId; }
    public boolean hasLaunched() { return hasLaunched; }
    public void setHasLaunched(boolean hasLaunched) { this.hasLaunched = hasLaunched; }

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
