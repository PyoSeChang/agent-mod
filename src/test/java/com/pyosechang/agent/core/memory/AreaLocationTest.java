package com.pyosechang.agent.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AreaLocationTest {

    private static final double DELTA = 0.001;

    // --- Coordinate normalization ---

    @Nested
    @DisplayName("Coordinate normalization")
    class Normalization {

        @Test
        @DisplayName("swapped corners are normalized (x1<x2, y1<y2, z1<z2)")
        void swappedCornersNormalized() {
            // Pass larger values first -- constructor should swap
            AreaLocation loc = new AreaLocation(10, 20, 30, 0, 5, 10);
            assertEquals(0, loc.getX1(), DELTA);
            assertEquals(5, loc.getY1(), DELTA);
            assertEquals(10, loc.getZ1(), DELTA);
            assertEquals(10, loc.getX2(), DELTA);
            assertEquals(20, loc.getY2(), DELTA);
            assertEquals(30, loc.getZ2(), DELTA);
        }

        @Test
        @DisplayName("default constructor initializes all to zero")
        void defaultConstructorAllZeros() {
            AreaLocation loc = new AreaLocation();
            assertEquals(0, loc.getX1(), DELTA);
            assertEquals(0, loc.getY1(), DELTA);
            assertEquals(0, loc.getZ1(), DELTA);
            assertEquals(0, loc.getX2(), DELTA);
            assertEquals(0, loc.getY2(), DELTA);
            assertEquals(0, loc.getZ2(), DELTA);
        }
    }

    // --- distanceTo() ---

    @Nested
    @DisplayName("distanceTo()")
    class DistanceTo {

        @Test
        @DisplayName("point inside area returns 0.0")
        void insideAreaReturnsZero() {
            AreaLocation loc = new AreaLocation(0, 0, 0, 10, 10, 10);
            assertEquals(0.0, loc.distanceTo(5, 5, 5), DELTA);
        }

        @Test
        @DisplayName("point outside one axis returns distance along that axis")
        void outsideOneAxis() {
            AreaLocation loc = new AreaLocation(0, 0, 0, 10, 10, 10);
            // x=15 is 5 past x2=10, y and z inside -> distance = 5
            assertEquals(5.0, loc.distanceTo(15, 5, 5), DELTA);
        }

        @Test
        @DisplayName("point outside corner returns diagonal distance")
        void outsideCorner() {
            AreaLocation loc = new AreaLocation(0, 0, 0, 10, 10, 10);
            // (13, 14, 10) -> closest point is (10, 10, 10), dx=3, dy=4, dz=0 -> 5.0
            assertEquals(5.0, loc.distanceTo(13, 14, 10), DELTA);
        }
    }

    // --- isWithinRange() ---

    @Nested
    @DisplayName("isWithinRange()")
    class IsWithinRange {

        @Test
        @DisplayName("point inside area with range 0 returns true")
        void insideWithZeroRange() {
            AreaLocation loc = new AreaLocation(0, 0, 0, 10, 10, 10);
            assertTrue(loc.isWithinRange(5, 5, 5, 0));
        }

        @Test
        @DisplayName("point outside area but within sufficient range returns true")
        void outsideWithSufficientRange() {
            AreaLocation loc = new AreaLocation(0, 0, 0, 10, 10, 10);
            // x=15 -> distance=5, range=10 -> within range
            assertTrue(loc.isWithinRange(15, 5, 5, 10));
        }

        @Test
        @DisplayName("point outside area with insufficient range returns false")
        void outsideWithInsufficientRange() {
            AreaLocation loc = new AreaLocation(0, 0, 0, 10, 10, 10);
            // x=15 -> distance=5, range=3 -> not within range
            assertFalse(loc.isWithinRange(15, 5, 5, 3));
        }
    }

    // --- getType() ---

    @Test
    @DisplayName("getType() returns 'area'")
    void getTypeReturnsArea() {
        AreaLocation loc = new AreaLocation(0, 0, 0, 10, 10, 10);
        assertEquals("area", loc.getType());
    }
}
