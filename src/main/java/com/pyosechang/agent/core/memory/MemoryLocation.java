package com.pyosechang.agent.core.memory;

import com.google.gson.JsonObject;

/**
 * Location model for memory entries.
 * Flat class for GSON compatibility — type field distinguishes point vs area.
 */
public class MemoryLocation {
    private String type; // "point" or "area"

    // Point fields
    private double x;
    private double y;
    private double z;
    private double radius;

    // Area fields (x1/z1/x2/z2 + y)
    private double x1;
    private double z1;
    private double x2;
    private double z2;

    public static MemoryLocation point(double x, double y, double z, double radius) {
        MemoryLocation loc = new MemoryLocation();
        loc.type = "point";
        loc.x = x;
        loc.y = y;
        loc.z = z;
        loc.radius = radius;
        return loc;
    }

    public static MemoryLocation area(double x1, double z1, double x2, double z2, double y) {
        MemoryLocation loc = new MemoryLocation();
        loc.type = "area";
        loc.x1 = Math.min(x1, x2);
        loc.z1 = Math.min(z1, z2);
        loc.x2 = Math.max(x1, x2);
        loc.z2 = Math.max(z1, z2);
        loc.y = y;
        return loc;
    }

    public static MemoryLocation fromJson(JsonObject json) {
        String type = json.get("type").getAsString();
        if ("area".equals(type)) {
            return area(
                json.get("x1").getAsDouble(),
                json.get("z1").getAsDouble(),
                json.get("x2").getAsDouble(),
                json.get("z2").getAsDouble(),
                json.get("y").getAsDouble()
            );
        } else {
            return point(
                json.get("x").getAsDouble(),
                json.get("y").getAsDouble(),
                json.get("z").getAsDouble(),
                json.has("radius") ? json.get("radius").getAsDouble() : 5.0
            );
        }
    }

    /**
     * Calculate distance from a point to this location.
     * For point: euclidean distance to center.
     * For area: 0 if inside, distance to nearest edge if outside.
     */
    public double distanceTo(double px, double py, double pz) {
        if ("area".equals(type)) {
            double cx = Math.max(x1, Math.min(px, x2));
            double cy = y;
            double cz = Math.max(z1, Math.min(pz, z2));
            double dx = px - cx;
            double dy = py - cy;
            double dz = pz - cz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        } else {
            double dx = px - x;
            double dy = py - y;
            double dz = pz - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * Check if a point is within this location's effective radius.
     * For point: within radius.
     * For area: within the rectangular bounds (with some vertical tolerance).
     */
    public boolean isWithinRange(double px, double py, double pz, double range) {
        return distanceTo(px, py, pz) <= range;
    }

    // Getters
    public String getType() { return type; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public double getRadius() { return radius; }
    public double getX1() { return x1; }
    public double getZ1() { return z1; }
    public double getX2() { return x2; }
    public double getZ2() { return z2; }
}
