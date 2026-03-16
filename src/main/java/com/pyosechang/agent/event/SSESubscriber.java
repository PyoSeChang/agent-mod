package com.pyosechang.agent.event;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class SSESubscriber implements EventSubscriber {
    private static final SSESubscriber INSTANCE = new SSESubscriber();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final byte[] HEARTBEAT = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;

    private final CopyOnWriteArrayList<OutputStream> connections = new CopyOnWriteArrayList<>();
    private final AtomicBoolean heartbeatRunning = new AtomicBoolean(false);

    private SSESubscriber() {}

    public static SSESubscriber getInstance() { return INSTANCE; }

    public void addConnection(OutputStream out) {
        connections.add(out);
        ensureHeartbeat();
    }

    public void removeConnection(OutputStream out) {
        connections.remove(out);
    }

    @Override
    public void onEvent(AgentEvent event) {
        byte[] sseBytes = event.toSSE().getBytes(StandardCharsets.UTF_8);
        for (OutputStream out : connections) {
            try {
                synchronized (out) {
                    out.write(sseBytes);
                    out.flush();
                }
            } catch (IOException e) {
                connections.remove(out);
            }
        }
    }

    private void ensureHeartbeat() {
        if (!heartbeatRunning.compareAndSet(false, true)) return;
        Thread heartbeat = new Thread(() -> {
            try {
                while (!connections.isEmpty()) {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    for (OutputStream out : connections) {
                        try {
                            synchronized (out) {
                                out.write(HEARTBEAT);
                                out.flush();
                            }
                        } catch (IOException e) {
                            connections.remove(out);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                heartbeatRunning.set(false);
            }
        }, "sse-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }
}
