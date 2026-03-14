package com.pyosechang.agent.core.memory;

/**
 * Memory for named areas (mining zone, grazing field, etc.).
 * Location is required (area).
 */
public class AreaMemory extends MemoryEntry implements Locatable {
    private AreaLocation location;

    public AreaMemory() {
        setCategory("area");
    }

    @Override
    public AreaLocation getLocation() { return location; }
    public void setLocation(AreaLocation location) { this.location = location; }
}
