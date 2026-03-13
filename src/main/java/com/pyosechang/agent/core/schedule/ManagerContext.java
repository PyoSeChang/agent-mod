package com.pyosechang.agent.core.schedule;

import com.pyosechang.agent.monitor.InterventionQueue;

import java.util.UUID;

/**
 * Manager-specific context. No FakePlayer — the manager has no physical body.
 * Holds intervention queue and runtime process state.
 */
public class ManagerContext {

    private final InterventionQueue interventionQueue;
    private String sessionId;
    private boolean hasLaunched = false;
    private Process runtimeProcess;

    public ManagerContext() {
        this.interventionQueue = new InterventionQueue();
        this.sessionId = UUID.randomUUID().toString();
    }

    public InterventionQueue getInterventionQueue() { return interventionQueue; }

    public String getSessionId() { return sessionId; }
    public boolean hasLaunched() { return hasLaunched; }
    public void setHasLaunched(boolean hasLaunched) { this.hasLaunched = hasLaunched; }

    public Process getRuntimeProcess() { return runtimeProcess; }
    public void setRuntimeProcess(Process process) { this.runtimeProcess = process; }

    public boolean isRuntimeRunning() {
        return runtimeProcess != null && runtimeProcess.isAlive();
    }

    public void resetSession() {
        this.sessionId = UUID.randomUUID().toString();
        this.hasLaunched = false;
    }
}
