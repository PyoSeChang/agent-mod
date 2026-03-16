package com.pyosechang.agent.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PointLocationTest {

    private static final double DELTA = 0.001;

    // --- distanceTo() ---

    @Nested
    @DisplayName("distanceTo()")
    class DistanceTo {

        @Test
        @DisplayName("same point returns 0.0")
        void samePointReturnsZero() {
            PointLocation loc = new PointLocation(10, 20, 30);
            assertEquals(0.0, loc.distanceTo(10, 20, 30), DELTA);
        }

        @Test
        @DisplayName("3-4-5 triangle returns 5.0")
        void pythagorean345() {
            PointLocation loc = new PointLocation(0, 0, 0);
            // dx=3, dy=4, dz=0 -> sqrt(9+16) = 5
            assertEquals(5.0, loc.distanceTo(3, 4, 0), DELTA);
        }

        @Test
        @DisplayName("3D diagonal returns sqrt(3)")
        void diagonalSqrt3() {
            PointLocation loc = new PointLocation(0, 0, 0);
            // dx=1, dy=1, dz=1 -> sqrt(3) ~ 1.732
            assertEquals(Math.sqrt(3), loc.distanceTo(1, 1, 1), DELTA);
        }

        @Test
        @DisplayName("negative coordinates work correctly")
        void negativeCoordinates() {
            PointLocation loc = new PointLocation(-5, -5, -5);
            // dx=5, dy=5, dz=5 -> sqrt(75) ~ 8.660
            assertEquals(Math.sqrt(75), loc.distanceTo(0, 0, 0), DELTA);
        }
    }

    // --- isWithinRange() ---

    @Nested
    @DisplayName("isWithinRange()")
    class IsWithinRange {

        @Test
        @DisplayName("point inside range returns true")
        void insideRange() {
            PointLocation loc = new PointLocation(0, 0, 0);
            assertTrue(loc.isWithinRange(1, 0, 0, 5.0));
        }

        @Test
        @DisplayName("point on boundary returns true (<=)")
        void onBoundary() {
            PointLocation loc = new PointLocation(0, 0, 0);
            // distance is exactly 5.0, range is 5.0
            assertTrue(loc.isWithinRange(3, 4, 0, 5.0));
        }

        @Test
        @DisplayName("point outside range returns false")
        void outsideRange() {
            PointLocation loc = new PointLocation(0, 0, 0);
            assertFalse(loc.isWithinRange(10, 10, 10, 5.0));
        }
    }

    // --- getType() ---

    @Test
    @DisplayName("getType() returns 'point'")
    void getTypeReturnsPoint() {
        PointLocation loc = new PointLocation(0, 0, 0);
        assertEquals("point", loc.getType());
    }
}
