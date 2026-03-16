package com.pyosechang.agent.core.schedule;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleConfigTest {

    // === Round-trip tests: toJson ->fromJson for all 3 types ===

    @Test
    void roundTrip_timeOfDay() {
        ScheduleConfig original = new ScheduleConfig();
        original.setType(ScheduleConfig.Type.TIME_OF_DAY);
        original.setTargetAgent("farmer");
        original.setEnabled(true);
        original.setTimeOfDay(6000);
        original.setRepeatDays(2);
        original.setRegisteredTick(100);
        original.setLastTriggeredTick(500);
        original.setLastTriggeredDay(3);

        JsonObject json = original.toJson();
        ScheduleConfig restored = ScheduleConfig.fromJson(json);

        assertEquals(ScheduleConfig.Type.TIME_OF_DAY, restored.getType());
        assertEquals("farmer", restored.getTargetAgent());
        assertTrue(restored.isEnabled());
        assertEquals(6000, restored.getTimeOfDay());
        assertEquals(2, restored.getRepeatDays());
        assertEquals(100, restored.getRegisteredTick());
        assertEquals(500, restored.getLastTriggeredTick());
        assertEquals(3, restored.getLastTriggeredDay());
    }

    @Test
    void roundTrip_interval() {
        ScheduleConfig original = new ScheduleConfig();
        original.setType(ScheduleConfig.Type.INTERVAL);
        original.setTargetAgent("miner");
        original.setEnabled(false);
        original.setIntervalTicks(2400);
        original.setRepeat(false);

        JsonObject json = original.toJson();
        ScheduleConfig restored = ScheduleConfig.fromJson(json);

        assertEquals(ScheduleConfig.Type.INTERVAL, restored.getType());
        assertEquals("miner", restored.getTargetAgent());
        assertFalse(restored.isEnabled());
        assertEquals(2400, restored.getIntervalTicks());
        assertFalse(restored.isRepeat());
    }

    @Test
    void roundTrip_observer() {
        ScheduleConfig original = new ScheduleConfig();
        original.setType(ScheduleConfig.Type.OBSERVER);
        original.setTargetAgent("guard");
        original.setThreshold(3);

        ObserverDef obs1 = new ObserverDef(10, 64, 20, "crop_grow", "age=7");
        ObserverDef obs2 = new ObserverDef(15, 64, 25, "block_break", null);
        original.setObservers(List.of(obs1, obs2));

        JsonObject json = original.toJson();
        ScheduleConfig restored = ScheduleConfig.fromJson(json);

        assertEquals(ScheduleConfig.Type.OBSERVER, restored.getType());
        assertEquals("guard", restored.getTargetAgent());
        assertEquals(3, restored.getThreshold());
        assertEquals(2, restored.getObservers().size());
        assertEquals("crop_grow", restored.getObservers().get(0).getEventType());
        assertEquals("age=7", restored.getObservers().get(0).getCondition());
        assertEquals("block_break", restored.getObservers().get(1).getEventType());
        assertNull(restored.getObservers().get(1).getCondition());
    }

    // === Default field values ===

    @Test
    void defaultFieldValues() {
        ScheduleConfig config = new ScheduleConfig();
        assertTrue(config.isEnabled(), "enabled defaults to true");
        assertEquals(1, config.getRepeatDays(), "repeatDays defaults to 1");
        assertTrue(config.isRepeat(), "repeat defaults to true");
        assertEquals(1, config.getThreshold(), "threshold defaults to 1");
    }

    // === fromJson with missing optional fields ->defaults applied ===

    @Test
    void fromJson_missingOptionalFields_defaultsApplied() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "INTERVAL");
        // No enabled, no target_agent, no interval_ticks, no repeat

        ScheduleConfig config = ScheduleConfig.fromJson(json);

        assertEquals(ScheduleConfig.Type.INTERVAL, config.getType());
        assertTrue(config.isEnabled(), "enabled defaults to true when missing");
        assertEquals("", config.getTargetAgent(), "target_agent defaults to empty string");
        assertEquals(1200, config.getIntervalTicks(), "interval_ticks defaults to 1200");
        assertTrue(config.isRepeat(), "repeat defaults to true when missing");
        assertEquals(-1, config.getLastTriggeredTick());
        assertEquals(-1, config.getLastTriggeredDay());
    }

    // === P1 bug fix: fromJson with invalid type ===

    @Test
    void fromJson_invalidType_throwsIllegalArgumentException() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "NONEXISTENT");

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ScheduleConfig.fromJson(json)
        );
        assertTrue(ex.getMessage().contains("Invalid schedule type"),
            "Expected message containing 'Invalid schedule type', got: " + ex.getMessage());
    }

    // === P1 bug fix: fromJson with missing type ===

    @Test
    void fromJson_missingType_throwsIllegalArgumentException() {
        JsonObject json = new JsonObject();
        json.addProperty("target_agent", "farmer");
        // No "type" field at all

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ScheduleConfig.fromJson(json)
        );
        assertTrue(ex.getMessage().contains("requires 'type' field"),
            "Expected message containing \"requires 'type' field\", got: " + ex.getMessage());
    }

    // === Boundary: timeOfDay values ===

    @Test
    void boundary_timeOfDay_zero() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "TIME_OF_DAY");
        json.addProperty("time_of_day", 0);

        ScheduleConfig config = ScheduleConfig.fromJson(json);
        assertEquals(0, config.getTimeOfDay());
    }

    @Test
    void boundary_timeOfDay_max() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "TIME_OF_DAY");
        json.addProperty("time_of_day", 23999);

        ScheduleConfig config = ScheduleConfig.fromJson(json);
        assertEquals(23999, config.getTimeOfDay());
    }

    // === P1 bug fix: intervalTicks=0 ->clamped to 1200 ===

    @Test
    void fromJson_intervalTicksZero_clampedTo1200() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "INTERVAL");
        json.addProperty("interval_ticks", 0);

        ScheduleConfig config = ScheduleConfig.fromJson(json);
        assertEquals(1200, config.getIntervalTicks(),
            "intervalTicks=0 should be clamped to default 1200");
    }

    // === timeOfDay negative ->accepted (documents missing validation) ===

    @Test
    void fromJson_timeOfDayNegative_accepted() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "TIME_OF_DAY");
        json.addProperty("time_of_day", -1);

        ScheduleConfig config = ScheduleConfig.fromJson(json);
        assertEquals(-1, config.getTimeOfDay(),
            "Negative timeOfDay is accepted (no validation)");
    }

    // === OBSERVER with observers list and threshold ===

    @Test
    void fromJson_observerWithListAndThreshold() {
        JsonObject obs = new JsonObject();
        obs.addProperty("x", 100);
        obs.addProperty("y", 65);
        obs.addProperty("z", 200);
        obs.addProperty("event", "entity_death");
        obs.addProperty("condition", "type=zombie");

        com.google.gson.JsonArray obsArr = new com.google.gson.JsonArray();
        obsArr.add(obs);

        JsonObject json = new JsonObject();
        json.addProperty("type", "OBSERVER");
        json.add("observers", obsArr);
        json.addProperty("threshold", 5);

        ScheduleConfig config = ScheduleConfig.fromJson(json);

        assertEquals(ScheduleConfig.Type.OBSERVER, config.getType());
        assertEquals(5, config.getThreshold());
        assertEquals(1, config.getObservers().size());

        ObserverDef restored = config.getObservers().get(0);
        assertEquals(100, restored.getX());
        assertEquals(65, restored.getY());
        assertEquals(200, restored.getZ());
        assertEquals("entity_death", restored.getEventType());
        assertEquals("type=zombie", restored.getCondition());
    }
}
