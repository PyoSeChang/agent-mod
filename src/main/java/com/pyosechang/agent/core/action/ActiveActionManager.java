package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.util.FakePlayer;
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

    private AsyncAction currentAction;
    private CompletableFuture<JsonObject> currentFuture;

    /**
     * Start a new async action, cancelling any currently running one.
     */
    public CompletableFuture<JsonObject> startAction(AsyncAction action, FakePlayer agent, JsonObject params) {
        // Cancel existing action
        if (currentAction != null && currentAction.isActive()) {
            LOGGER.debug("Cancelling current action {} for new action {}",
                currentAction.getName(), action.getName());
            currentAction.cancel();
        }

        currentAction = action;
        currentFuture = action.start(agent, params);
        return currentFuture;
    }

    /**
     * Tick the current async action. Called every server tick from AgentTickHandler.
     */
    public void tick(FakePlayer agent) {
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
