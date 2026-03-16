package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.event.AgentEvent;
import com.pyosechang.agent.event.EventBus;
import com.pyosechang.agent.event.EventType;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Manages the single currently-running async action for one agent.
 * Starting a new action automatically cancels the previous one
 * (just like a real player stops walking when they start mining).
 *
 * Each AgentContext holds its own instance.
 */
public class ActiveActionManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String agentName;
    private AsyncAction currentAction;
    private CompletableFuture<JsonObject> currentFuture;

    public ActiveActionManager(String agentName) {
        this.agentName = agentName;
    }

    /**
     * Start a new async action, cancelling any currently running one.
     */
    public CompletableFuture<JsonObject> startAction(AsyncAction action, ServerPlayer agent, JsonObject params) {
        // Cancel existing action
        if (currentAction != null && currentAction.isActive()) {
            LOGGER.debug("Cancelling current action {} for new action {}",
                currentAction.getName(), action.getName());
            currentAction.cancel();
        }

        currentAction = action;

        JsonObject startData = new JsonObject();
        startData.addProperty("action", action.getName());
        EventBus.getInstance().publish(AgentEvent.of(agentName, EventType.ACTION_STARTED, startData));

        currentFuture = action.start(agent, params);
        currentFuture.whenComplete((result, ex) -> {
            JsonObject data = new JsonObject();
            data.addProperty("action", action.getName());
            if (ex != null) {
                data.addProperty("error", ex.getMessage());
                EventBus.getInstance().publish(AgentEvent.of(agentName, EventType.ACTION_FAILED, data));
            } else if (result != null && result.has("ok") && !result.get("ok").getAsBoolean()) {
                data.addProperty("error", result.has("error") ? result.get("error").getAsString() : "unknown");
                EventBus.getInstance().publish(AgentEvent.of(agentName, EventType.ACTION_FAILED, data));
            } else {
                EventBus.getInstance().publish(AgentEvent.of(agentName, EventType.ACTION_COMPLETED, data));
            }
        });
        return currentFuture;
    }

    /**
     * Tick the current async action. Called every server tick from AgentTickHandler.
     */
    public void tick(ServerPlayer agent) {
        if (currentAction != null && currentAction.isActive()) {
            currentAction.tick(agent);
        }
    }

    /**
     * Cancel the current action.
     */
    public void cancel() {
        if (currentAction != null && currentAction.isActive()) {
            currentAction.cancel();
            currentAction = null;
        }
    }

    /**
     * @return the currently running action, or null
     */
    public AsyncAction getCurrentAction() {
        return currentAction;
    }
}
