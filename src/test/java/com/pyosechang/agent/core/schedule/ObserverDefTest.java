package com.pyosechang.agent.core.schedule;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObserverDefTest {

    // === toJson/fromJson round-trip with condition ===

    @Test
    void roundTrip_withCondition() {
        ObserverDef original = new ObserverDef(10, 64, 20, "crop_grow", "age=7");

        JsonObject json = original.toJson();
        ObserverDef restored = ObserverDef.fromJson(json);

        assertEquals(10, restored.getX());
        assertEquals(64, restored.getY());
        assertEquals(20, restored.getZ());
        assertEquals("crop_grow", restored.getEventType());
        assertEquals("age=7", restored.getCondition());
    }

    // === toJson omits null/empty condition ===

    @Test
    void toJson_omitsNullCondition() {
        ObserverDef def = new ObserverDef(5, 70, 15, "block_break", null);

        JsonObject json = def.toJson();

        assertTrue(json.has("x"));
        assertTrue(json.has("y"));
        assertTrue(json.has("z"));
        assertTrue(json.has("event"));
        assertFalse(json.has("condition"), "null condition should be omitted from JSON");
    }

    @Test
    void toJson_omitsEmptyCondition() {
        ObserverDef def = new ObserverDef(5, 70, 15, "block_break", "");

        JsonObject json = def.toJson();

        assertFalse(json.has("condition"), "empty condition should be omitted from JSON");
    }

    // === fromJson with missing "event" key ->empty string ===

    @Test
    void fromJson_missingEvent_defaultsToEmptyString() {
        JsonObject json = new JsonObject();
        json.addProperty("x", 1);
        json.addProperty("y", 2);
        json.addProperty("z", 3);
        // No "event" key

        ObserverDef def = ObserverDef.fromJson(json);

        assertEquals("", def.getEventType(), "Missing event should default to empty string");
        assertEquals(1, def.getX());
        assertEquals(2, def.getY());
        assertEquals(3, def.getZ());
        assertNull(def.getCondition());
    }

    // === P1 bug fix: fromJson with missing coordinate ->IllegalArgumentException ===

    @Test
    void fromJson_missingCoordinate_throwsIllegalArgumentException() {
        // Missing z
        JsonObject json = new JsonObject();
        json.addProperty("x", 1);
        json.addProperty("y", 2);
        json.addProperty("event", "crop_grow");

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ObserverDef.fromJson(json)
        );
        assertTrue(ex.getMessage().contains("requires x, y, z"),
            "Expected message containing 'requires x, y, z', got: " + ex.getMessage());
    }

    // === fromJson with all fields present ->correct values ===

    @Test
    void fromJson_allFieldsPresent() {
        JsonObject json = new JsonObject();
        json.addProperty("x", -100);
        json.addProperty("y", 128);
        json.addProperty("z", 300);
        json.addProperty("event", "explosion");
        json.addProperty("condition", "power>4");

        ObserverDef def = ObserverDef.fromJson(json);

        assertEquals(-100, def.getX());
        assertEquals(128, def.getY());
        assertEquals(300, def.getZ());
        assertEquals("explosion", def.getEventType());
        assertEquals("power>4", def.getCondition());
    }
}
