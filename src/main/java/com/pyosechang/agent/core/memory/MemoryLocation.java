package com.pyosechang.agent.core.memory;

/**
 * Location model for memory entries.
 * Implementations: PointLocation, AreaLocation.
 */
public interface MemoryLocation {
    String getType();
    double distanceTo(double px, double py, double pz);
    boolean isWithinRange(double px, double py, double pz, double range);
}
