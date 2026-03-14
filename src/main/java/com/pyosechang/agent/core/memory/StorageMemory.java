package com.pyosechang.agent.core.memory;

/**
 * Memory for storage locations (chests, barrels, etc.).
 * Location can be point (single block) or area (double chest, multi-block).
 */
public class StorageMemory extends MemoryEntry implements Locatable {
    private MemoryLocation location;

    public StorageMemory() {
        setCategory("storage");
    }

    @Override
    public MemoryLocation getLocation() { return location; }
    public void setLocation(MemoryLocation location) { this.location = location; }
}
