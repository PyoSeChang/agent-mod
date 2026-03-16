package com.pyosechang.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InfraCanaryTest {

    @Test
    void junitWorks() {
        assertTrue(true, "JUnit 5 pipeline works");
    }

    @Test
    void gsonOnClasspath() {
        // Gson is bundled with Minecraft -verify it's available
        var obj = new com.google.gson.JsonObject();
        obj.addProperty("test", true);
        assertTrue(obj.get("test").getAsBoolean());
    }

    @Test
    void minecraftClassesOnClasspath() {
        // BlockPos from net.minecraft.core -verify ForgeGradle bridge works
        var pos = new net.minecraft.core.BlockPos(10, 64, 20);
        assertEquals(10, pos.getX());
        assertEquals(64, pos.getY());
        assertEquals(20, pos.getZ());
    }
}
