package com.pyosechang.agent.core.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryLocationTypeAdapterTest {

    private Gson gson;

    @BeforeEach
    void setUp() {
        gson = new GsonBuilder()
            .registerTypeAdapter(MemoryLocation.class, new MemoryLocationTypeAdapter())
            .create();
    }

    // --- Deserialize ---

    @Test
    void deserializePoint() {
        String json = """
            {"type":"point","x":10,"y":64,"z":20}""";
        MemoryLocation loc = gson.fromJson(json, MemoryLocation.class);

        assertInstanceOf(PointLocation.class, loc);
        PointLocation pt = (PointLocation) loc;
        assertEquals("point", pt.getType());
        assertEquals(10, pt.getX());
        assertEquals(64, pt.getY());
        assertEquals(20, pt.getZ());
    }

    @Test
    void deserializeAreaWithY1Y2() {
        String json = """
            {"type":"area","x1":0,"y1":60,"z1":0,"x2":100,"y2":80,"z2":100}""";
        MemoryLocation loc = gson.fromJson(json, MemoryLocation.class);

        assertInstanceOf(AreaLocation.class, loc);
        AreaLocation area = (AreaLocation) loc;
        assertEquals("area", area.getType());
        assertEquals(0, area.getX1());
        assertEquals(60, area.getY1());
        assertEquals(0, area.getZ1());
        assertEquals(100, area.getX2());
        assertEquals(80, area.getY2());
        assertEquals(100, area.getZ2());
    }

    @Test
    void backwardCompat_areaSingleY_setsY1AndY2() {
        String json = """
            {"type":"area","x1":10,"z1":20,"x2":30,"z2":40,"y":65}""";
        MemoryLocation loc = gson.fromJson(json, MemoryLocation.class);

        assertInstanceOf(AreaLocation.class, loc);
        AreaLocation area = (AreaLocation) loc;
        assertEquals(65, area.getY1());
        assertEquals(65, area.getY2());
    }

    @Test
    void backwardCompat_pointIgnoresOldRadiusField() {
        String json = """
            {"type":"point","x":5,"y":70,"z":15,"radius":10}""";
        MemoryLocation loc = gson.fromJson(json, MemoryLocation.class);

        assertInstanceOf(PointLocation.class, loc);
        PointLocation pt = (PointLocation) loc;
        assertEquals(5, pt.getX());
        assertEquals(70, pt.getY());
        assertEquals(15, pt.getZ());
    }

    @Test
    void missingType_defaultsToPoint() {
        String json = """
            {"x":1,"y":2,"z":3}""";
        MemoryLocation loc = gson.fromJson(json, MemoryLocation.class);

        assertInstanceOf(PointLocation.class, loc);
        PointLocation pt = (PointLocation) loc;
        assertEquals(1, pt.getX());
        assertEquals(2, pt.getY());
        assertEquals(3, pt.getZ());
    }

    @Test
    void missingCoordinates_defaultToZero() {
        String json = """
            {"type":"point"}""";
        MemoryLocation loc = gson.fromJson(json, MemoryLocation.class);

        assertInstanceOf(PointLocation.class, loc);
        PointLocation pt = (PointLocation) loc;
        assertEquals(0, pt.getX());
        assertEquals(0, pt.getY());
        assertEquals(0, pt.getZ());
    }

    // --- Round-trip ---

    @Test
    void roundTrip_point() {
        PointLocation original = new PointLocation(42, 128, -77);
        String json = gson.toJson(original, MemoryLocation.class);
        MemoryLocation restored = gson.fromJson(json, MemoryLocation.class);

        assertInstanceOf(PointLocation.class, restored);
        PointLocation pt = (PointLocation) restored;
        assertEquals(42, pt.getX());
        assertEquals(128, pt.getY());
        assertEquals(-77, pt.getZ());
    }

    @Test
    void roundTrip_area() {
        AreaLocation original = new AreaLocation(10, 60, 20, 50, 80, 70);
        String json = gson.toJson(original, MemoryLocation.class);
        MemoryLocation restored = gson.fromJson(json, MemoryLocation.class);

        assertInstanceOf(AreaLocation.class, restored);
        AreaLocation area = (AreaLocation) restored;
        assertEquals(10, area.getX1());
        assertEquals(60, area.getY1());
        assertEquals(20, area.getZ1());
        assertEquals(50, area.getX2());
        assertEquals(80, area.getY2());
        assertEquals(70, area.getZ2());
    }
}
