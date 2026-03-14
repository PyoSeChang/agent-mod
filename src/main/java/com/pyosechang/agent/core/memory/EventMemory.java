package com.pyosechang.agent.core.memory;

/**
 * Memory for events (things that happened). Location is optional.
 */
public class EventMemory extends MemoryEntry implements Locatable {
    private MemoryLocation location;

    public EventMemory() {
        setCategory("event");
    }

    @Override
    public MemoryLocation getLocation() { return location; }
    public void setLocation(MemoryLocation location) { this.location = location; }
}
