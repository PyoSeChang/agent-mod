package com.pyosechang.agent.core.memory;

public class PointLocation implements MemoryLocation {
    private final String type = "point";
    private double x;
    private double y;
    private double z;

    public PointLocation() {}

    public PointLocation(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public String getType() { return type; }

    @Override
    public double distanceTo(double px, double py, double pz) {
        double dx = px - x;
        double dy = py - y;
        double dz = pz - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public boolean isWithinRange(double px, double py, double pz, double range) {
        return distanceTo(px, py, pz) <= range;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
}
