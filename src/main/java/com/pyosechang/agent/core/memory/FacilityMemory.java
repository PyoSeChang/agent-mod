package com.pyosechang.agent.core.memory;

/**
 * Memory for facilities (farms, smelters, workshops, etc.).
 * Location can be point or area.
 */
public class FacilityMemory extends MemoryEntry implements Locatable {
    private MemoryLocation location;

    public FacilityMemory() {
        setCategory("facility");
    }

    @Override
    public MemoryLocation getLocation() { return location; }
    public void setLocation(MemoryLocation location) { this.location = location; }
}
