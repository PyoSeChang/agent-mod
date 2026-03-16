package com.pyosechang.agent.core.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ManagerContextTest {

    private ManagerContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new ManagerContext();
    }

    @Test
    void initialState_sessionIdNonNull_hasLaunchedFalse_processNull_notRunning() {
        assertNotNull(ctx.getSessionId());
        assertFalse(ctx.getSessionId().isEmpty());
        assertFalse(ctx.hasLaunched());
        assertNull(ctx.getRuntimeProcess());
        assertFalse(ctx.isRuntimeRunning());
    }

    @Test
    void resetSession_changesSessionId_andResetsHasLaunched() {
        String oldId = ctx.getSessionId();
        ctx.setHasLaunched(true);

        ctx.resetSession();

        assertNotEquals(oldId, ctx.getSessionId());
        assertFalse(ctx.hasLaunched());
    }

    @Test
    void isRuntimeRunning_nullProcess_returnsFalse() {
        // runtimeProcess is null by default
        assertFalse(ctx.isRuntimeRunning());
    }

    @Test
    void interventionQueue_isInitialized() {
        assertNotNull(ctx.getInterventionQueue());
    }
}
