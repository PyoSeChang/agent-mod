package com.pyosechang.agent.core.memory;

/**
 * Marker interface for memory entries that have a location.
 */
public interface Locatable {
    MemoryLocation getLocation();
}
