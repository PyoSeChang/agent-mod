package com.pyosechang.agent.core.memory;

import com.google.gson.*;
import com.pyosechang.agent.core.schedule.ScheduleConfig;
import com.pyosechang.agent.core.schedule.ScheduleConfigTypeAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryEntryTypeAdapterTest {

    private Gson gson;

    @BeforeEach
    void setUp() {
        gson = new GsonBuilder()
            .registerTypeAdapter(MemoryEntry.class, new MemoryEntryTypeAdapter())
            .registerTypeAdapter(MemoryLocation.class, new MemoryLocationTypeAdapter())
            .registerTypeAdapter(ScheduleConfig.class, new ScheduleConfigTypeAdapter())
            .create();
    }

    // --- Deserialize each category ---

    @Test
    void deserializeStorage_withPointLocation() {
        String json = """
            {
              "id":"s1","title":"Oak chest","description":"Main storage",
              "category":"storage",
              "location":{"type":"point","x":100,"y":64,"z":200}
            }""";
        MemoryEntry entry = gson.fromJson(json, MemoryEntry.class);

        assertInstanceOf(StorageMemory.class, entry);
        assertEquals("s1", entry.getId());
        assertEquals("Oak chest", entry.getTitle());
        assertEquals("storage", entry.getCategory());

        StorageMemory sm = (StorageMemory) entry;
        assertNotNull(sm.getLocation());
        assertInstanceOf(PointLocation.class, sm.getLocation());
        assertEquals(100, ((PointLocation) sm.getLocation()).getX());
    }

    @Test
    void deserializeFacility_withAreaLocation() {
        String json = """
            {
              "id":"f1","title":"Wheat farm","description":"Auto farm",
              "category":"facility",
              "location":{"type":"area","x1":0,"y1":63,"z1":0,"x2":16,"y2":63,"z2":16}
            }""";
        MemoryEntry entry = gson.fromJson(json, MemoryEntry.class);

        assertInstanceOf(FacilityMemory.class, entry);
        FacilityMemory fm = (FacilityMemory) entry;
        assertNotNull(fm.getLocation());
        assertInstanceOf(AreaLocation.class, fm.getLocation());
        AreaLocation area = (AreaLocation) fm.getLocation();
        assertEquals(0, area.getX1());
        assertEquals(16, area.getX2());
    }

    @Test
    void deserializeArea() {
        String json = """
            {
              "id":"a1","title":"Mining zone","description":"Diamond level",
              "category":"area",
              "location":{"type":"area","x1":-50,"y1":5,"z1":-50,"x2":50,"y2":15,"z2":50}
            }""";
        MemoryEntry entry = gson.fromJson(json, MemoryEntry.class);

        assertInstanceOf(AreaMemory.class, entry);
        AreaMemory am = (AreaMemory) entry;
        assertNotNull(am.getLocation());
        assertEquals(5, am.getLocation().getY1());
    }

    @Test
    void deserializeEvent_noLocation() {
        String json = """
            {
              "id":"e1","title":"Creeper explosion","description":"Near base",
              "category":"event"
            }""";
        MemoryEntry entry = gson.fromJson(json, MemoryEntry.class);

        assertInstanceOf(EventMemory.class, entry);
        EventMemory em = (EventMemory) entry;
        assertNull(em.getLocation());
    }

    @Test
    void deserializeSkill() {
        String json = """
            {
              "id":"sk1","title":"Auto-smelt","description":"Learned furnace workflow",
              "category":"skill","content":"Place fuel, then ore"
            }""";
        MemoryEntry entry = gson.fromJson(json, MemoryEntry.class);

        assertInstanceOf(SkillMemory.class, entry);
        assertEquals("Place fuel, then ore", entry.getContent());
    }

    @Test
    void deserializeSchedule_withConfig() {
        String json = """
            {
              "id":"sch1","title":"Morning harvest","description":"Daily",
              "category":"schedule","content":"Harvest wheat",
              "config":{
                "type":"TIME_OF_DAY","target_agent":"alex",
                "enabled":true,"time_of_day":0,"repeat_days":1
              }
            }""";
        MemoryEntry entry = gson.fromJson(json, MemoryEntry.class);

        assertInstanceOf(ScheduleMemory.class, entry);
        ScheduleMemory sm = (ScheduleMemory) entry;
        assertNotNull(sm.getConfig());
        assertEquals(ScheduleConfig.Type.TIME_OF_DAY, sm.getConfig().getType());
        assertEquals("alex", sm.getConfig().getTargetAgent());
        assertEquals(0, sm.getConfig().getTimeOfDay());
        assertEquals("Harvest wheat", sm.getContent());
    }

    // --- Unknown/null category ---

    @Test
    void unknownCategory_returnsBaseMemoryEntry() {
        String json = """
            {"id":"u1","title":"Unknown","category":"custom_type"}""";
        MemoryEntry entry = gson.fromJson(json, MemoryEntry.class);

        assertNotNull(entry);
        assertEquals("u1", entry.getId());
        // Should be base MemoryEntry, not any subclass
        assertEquals(MemoryEntry.class, entry.getClass());
    }

    @Test
    void nullCategory_throwsOnDeserialize() {
        // Java switch expression throws NPE on null selector before reaching default.
        String json = """
            {"id":"n1","title":"No category"}""";
        assertThrows(NullPointerException.class, () ->
            gson.fromJson(json, MemoryEntry.class)
        );
    }

    // --- Round-trip ---

    @Test
    void roundTrip_storageMemory() {
        StorageMemory original = new StorageMemory();
        original.setId("rt-s1");
        original.setTitle("Iron chest");
        original.setDescription("Sorted iron ingots");
        original.setContent("64 iron ingots");
        original.setLocation(new PointLocation(50, 64, 100));

        String json = gson.toJson(original, MemoryEntry.class);
        MemoryEntry restored = gson.fromJson(json, MemoryEntry.class);

        assertInstanceOf(StorageMemory.class, restored);
        assertEquals("rt-s1", restored.getId());
        assertEquals("Iron chest", restored.getTitle());
        assertEquals("Sorted iron ingots", restored.getDescription());
        assertEquals("64 iron ingots", restored.getContent());
        assertEquals("storage", restored.getCategory());

        StorageMemory restoredSm = (StorageMemory) restored;
        assertInstanceOf(PointLocation.class, restoredSm.getLocation());
        PointLocation pt = (PointLocation) restoredSm.getLocation();
        assertEquals(50, pt.getX());
        assertEquals(64, pt.getY());
        assertEquals(100, pt.getZ());
    }

    @Test
    void roundTrip_scheduleMemory() {
        ScheduleMemory original = new ScheduleMemory();
        original.setId("rt-sch1");
        original.setTitle("Patrol");
        original.setDescription("Guard base perimeter");
        original.setContent("Walk the perimeter route");

        ScheduleConfig config = new ScheduleConfig();
        config.setType(ScheduleConfig.Type.INTERVAL);
        config.setTargetAgent("steve");
        config.setIntervalTicks(2400);
        config.setRepeat(true);
        original.setConfig(config);

        String json = gson.toJson(original, MemoryEntry.class);
        MemoryEntry restored = gson.fromJson(json, MemoryEntry.class);

        assertInstanceOf(ScheduleMemory.class, restored);
        ScheduleMemory restoredSm = (ScheduleMemory) restored;
        assertEquals("rt-sch1", restoredSm.getId());
        assertEquals("Walk the perimeter route", restoredSm.getContent());
        assertNotNull(restoredSm.getConfig());
        assertEquals(ScheduleConfig.Type.INTERVAL, restoredSm.getConfig().getType());
        assertEquals("steve", restoredSm.getConfig().getTargetAgent());
        assertEquals(2400, restoredSm.getConfig().getIntervalTicks());
        assertTrue(restoredSm.getConfig().isRepeat());
    }

    // --- Backward compat: scope migration ---

    @Test
    void backwardCompat_scopeAgentColon_migratesVisibleTo() {
        String json = """
            {"id":"bc1","title":"Alex note","category":"skill","scope":"agent:alex"}""";
        MemoryEntry entry = gson.fromJson(json, MemoryEntry.class);

        assertEquals(1, entry.getVisibleTo().size());
        assertEquals("alex", entry.getVisibleTo().get(0));
    }

    @Test
    void backwardCompat_scopeAgentsColon_migratesVisibleTo() {
        String json = """
            {"id":"bc2","title":"Shared note","category":"skill","scope":"agents:alex,steve"}""";
        MemoryEntry entry = gson.fromJson(json, MemoryEntry.class);

        assertEquals(2, entry.getVisibleTo().size());
        assertTrue(entry.getVisibleTo().contains("alex"));
        assertTrue(entry.getVisibleTo().contains("steve"));
    }

    @Test
    void backwardCompat_scopeGlobal_doesNotMigrate() {
        String json = """
            {"id":"bc3","title":"Global note","category":"skill","scope":"global"}""";
        MemoryEntry entry = gson.fromJson(json, MemoryEntry.class);

        assertTrue(entry.getVisibleTo().isEmpty());
        assertTrue(entry.isGlobal());
    }

    // --- Backward compat: old schedule format (config in content string) ---

    @Test
    void backwardCompat_oldScheduleFormat_configInContent() {
        // Old format: config was serialized as JSON inside the content string field
        String configJson = """
            {"type":"TIME_OF_DAY","target_agent":"alex","time_of_day":6000,"repeat_days":1,"prompt_message":"Water the crops"}""";
        // Escape for embedding in outer JSON
        String escapedConfig = configJson.replace("\"", "\\\"").replace("\n", "");
        String json = String.format("""
            {"id":"old1","title":"Old schedule","category":"schedule","content":"%s"}""", escapedConfig);
        MemoryEntry entry = gson.fromJson(json, MemoryEntry.class);

        assertInstanceOf(ScheduleMemory.class, entry);
        ScheduleMemory sm = (ScheduleMemory) entry;
        assertNotNull(sm.getConfig());
        assertEquals(ScheduleConfig.Type.TIME_OF_DAY, sm.getConfig().getType());
        assertEquals("alex", sm.getConfig().getTargetAgent());
        assertEquals(6000, sm.getConfig().getTimeOfDay());
        // Note: fillBaseFields overwrites content with the raw JSON string,
        // but the config was already parsed from it before that step
    }

    // --- Serialize includes base fields ---

    @Test
    void serialize_includesBaseFields() {
        SkillMemory skill = new SkillMemory();
        skill.setId("ser1");
        skill.setTitle("Mining");
        skill.setDescription("Efficient strip mining");
        skill.setContent("Y=11 strip mine technique");

        String json = gson.toJson(skill, MemoryEntry.class);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("ser1", obj.get("id").getAsString());
        assertEquals("Mining", obj.get("title").getAsString());
        assertEquals("Efficient strip mining", obj.get("description").getAsString());
        assertEquals("Y=11 strip mine technique", obj.get("content").getAsString());
        assertEquals("skill", obj.get("category").getAsString());
        assertTrue(obj.has("visibleTo"));
        assertTrue(obj.has("createdAt"));
    }

    // --- Serialize includes location for Locatable ---

    @Test
    void serialize_includesLocationForLocatable() {
        StorageMemory storage = new StorageMemory();
        storage.setId("loc1");
        storage.setTitle("Chest");
        storage.setLocation(new PointLocation(10, 64, 20));

        String json = gson.toJson(storage, MemoryEntry.class);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        assertTrue(obj.has("location"));
        JsonObject loc = obj.getAsJsonObject("location");
        assertEquals("point", loc.get("type").getAsString());
        assertEquals(10.0, loc.get("x").getAsDouble());
        assertEquals(64.0, loc.get("y").getAsDouble());
        assertEquals(20.0, loc.get("z").getAsDouble());
    }

    // --- Non-JSON input ---

    @Test
    void nonJsonInput_throwsJsonParseException() {
        assertThrows(JsonParseException.class, () ->
            gson.fromJson("not valid json{{{", MemoryEntry.class)
        );
    }
}
