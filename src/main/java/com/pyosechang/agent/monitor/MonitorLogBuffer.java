package com.pyosechang.agent.monitor;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class MonitorLogBuffer {
    private static final MonitorLogBuffer INSTANCE = new MonitorLogBuffer();
    private static final int MAX_SIZE = 500;

    private final LinkedList<LogEntry> buffer = new LinkedList<>();
    private int readIndex = 0;

    public static MonitorLogBuffer getInstance() { return INSTANCE; }

    public synchronized void add(String type, String message) {
        buffer.addLast(new LogEntry(type, message, System.currentTimeMillis()));
        while (buffer.size() > MAX_SIZE) {
            buffer.removeFirst();
        }
    }

    public synchronized List<LogEntry> getNew() {
        List<LogEntry> newEntries = new ArrayList<>();
        int currentSize = buffer.size();
        if (readIndex < currentSize) {
            newEntries.addAll(buffer.subList(readIndex, currentSize));
            readIndex = currentSize;
        }
        return newEntries;
    }

    public synchronized List<LogEntry> getAll() {
        return new ArrayList<>(buffer);
    }

    public synchronized void clear() {
        buffer.clear();
        readIndex = 0;
    }

    public record LogEntry(String type, String message, long timestamp) {}
}
