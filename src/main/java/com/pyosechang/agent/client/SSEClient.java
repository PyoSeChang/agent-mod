package com.pyosechang.agent.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Client-side SSE reader that streams from /events/stream.
 * Runs on a daemon thread, dispatches events via callback.
 */
public class SSEClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SSEClient.class);

    public record SSEEvent(String type, String data) {}

    private final Consumer<SSEEvent> callback;
    private volatile Thread readerThread;
    private volatile InputStream stream;
    private volatile boolean connected;
    private volatile String status = "Disconnected";

    public SSEClient(Consumer<SSEEvent> callback) {
        this.callback = callback;
    }

    public void connect() {
        if (readerThread != null) return;

        readerThread = new Thread(this::readLoop, "sse-client");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void disconnect() {
        connected = false;
        status = "Disconnected";
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) {}
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    public boolean isConnected() { return connected; }
    public String getStatus() { return status; }

    private void readLoop() {
        try {
            int port = BridgeClient.getPort();
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/events/stream"))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();

            status = "Connecting...";
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                status = "Error: HTTP " + response.statusCode();
                return;
            }

            stream = response.body();
            connected = true;
            status = "Connected";

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {

                String eventType = "";
                StringBuilder dataBuilder = new StringBuilder();

                String line;
                while ((line = reader.readLine()) != null) {
                    if (!connected) break;

                    if (line.startsWith("event: ")) {
                        eventType = line.substring(7).trim();
                    } else if (line.startsWith("data: ")) {
                        if (dataBuilder.length() > 0) dataBuilder.append('\n');
                        dataBuilder.append(line.substring(6));
                    } else if (line.startsWith(":")) {
                        // heartbeat comment, ignore
                    } else if (line.isEmpty()) {
                        // blank line = dispatch event
                        if (!eventType.isEmpty() && dataBuilder.length() > 0) {
                            String type = eventType;
                            String data = dataBuilder.toString();
                            callback.accept(new SSEEvent(type, data));
                        }
                        eventType = "";
                        dataBuilder.setLength(0);
                    }
                }
            }
        } catch (Exception e) {
            if (connected) {
                LOGGER.warn("SSE connection lost: {}", e.getMessage());
            }
        } finally {
            connected = false;
            status = "Disconnected";
            readerThread = null;
        }
    }
}
