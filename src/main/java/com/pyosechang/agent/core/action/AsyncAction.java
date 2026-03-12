package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraftforge.common.util.FakePlayer;

import java.util.concurrent.CompletableFuture;

/**
 * An action that spans multiple ticks (walking, mining, etc.).
 * The HTTP bridge awaits the returned future instead of blocking on execute().
 */
public interface AsyncAction extends Action {

    /**
     * Start the async action. Returns a future that completes when the action finishes.
     * The default execute() delegates to start() and blocks, so sync callers still work.
     */
    CompletableFuture<JsonObject> start(FakePlayer agent, JsonObject params);

    /** Called every server tick while this action is active. */
    void tick(FakePlayer agent);

    /** Cancel the action early. */
    void cancel();

    /** Whether this action is currently running. */
    boolean isActive();

    /** Timeout in milliseconds. HTTP bridge uses this instead of hardcoded 60s. */
    default long getTimeoutMs() { return 60_000; }

    /**
     * Default sync implementation — starts and immediately returns the future's result.
     * HTTP bridge should check instanceof AsyncAction and use start() directly.
     */
    @Override
    default JsonObject execute(FakePlayer agent, JsonObject params) {
        CompletableFuture<JsonObject> future = start(agent, params);
        try {
            return future.get();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }
}
