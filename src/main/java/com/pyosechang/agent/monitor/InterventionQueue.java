package com.pyosechang.agent.monitor;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-agent intervention message queue.
 * Each AgentContext holds its own instance.
 */
public class InterventionQueue {
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    public void add(String message) { queue.add(message); }
    public String poll() { return queue.poll(); }
    public boolean hasMessages() { return !queue.isEmpty(); }
    public void clear() { queue.clear(); }
}
