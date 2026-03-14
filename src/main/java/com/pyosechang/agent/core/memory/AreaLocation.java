package com.pyosechang.agent.core.memory;

public class AreaLocation implements MemoryLocation {
    private final String type = "area";
    private double x1;
    private double y1;
    private double z1;
    private double x2;
    private double y2;
    private double z2;

    public AreaLocation() {}

    public AreaLocation(double x1, double y1, double z1, double x2, double y2, double z2) {
        this.x1 = Math.min(x1, x2);
        this.y1 = Math.min(y1, y2);
        this.z1 = Math.min(z1, z2);
        this.x2 = Math.max(x1, x2);
        this.y2 = Math.max(y1, y2);
        this.z2 = Math.max(z1, z2);
    }

    @Override
    public String getType() { return type; }

    @Override
    public double distanceTo(double px, double py, double pz) {
        double cx = Math.max(x1, Math.min(px, x2));
        double cy = Math.max(y1, Math.min(py, y2));
        double cz = Math.max(z1, Math.min(pz, z2));
        double dx = px - cx;
        double dy = py - cy;
        double dz = pz - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public boolean isWithinRange(double px, double py, double pz, double range) {
        return distanceTo(px, py, pz) <= range;
    }

    public double getX1() { return x1; }
    public double getY1() { return y1; }
    public double getZ1() { return z1; }
    public double getX2() { return x2; }
    public double getY2() { return y2; }
    public double getZ2() { return z2; }
}
