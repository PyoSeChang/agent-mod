package com.pyosechang.agent.monitor;

import java.util.concurrent.ConcurrentLinkedQueue;

public class InterventionQueue {
    private static final InterventionQueue INSTANCE = new InterventionQueue();
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    public static InterventionQueue getInstance() { return INSTANCE; }

    public void add(String message) { queue.add(message); }
    public String poll() { return queue.poll(); }
    public boolean hasMessages() { return !queue.isEmpty(); }
    public void clear() { queue.clear(); }
}
