package com.pyosechang.agent.core.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.core.AgentLogger;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Executes a sequence of actions one after another.
 * Sync actions execute immediately; async actions are ticked until completion.
 * On step failure, the sequence aborts and returns partial results.
 */
public class SequenceAction implements AsyncAction {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CompletableFuture<JsonObject> future;
    private boolean active = false;

    private List<JsonObject> steps;
    private int currentStepIndex;
    private JsonArray results;

    // Current async sub-action (null if waiting for next step or running sync)
    private AsyncAction currentSubAction;

    @Override
    public String getName() { return "execute_sequence"; }

    @Override
    public long getTimeoutMs() { return 300_000; } // 5 minutes

    @Override
    public CompletableFuture<JsonObject> start(ServerPlayer agent, JsonObject params) {
        future = new CompletableFuture<>();
        active = false;

        if (!params.has("steps") || !params.get("steps").isJsonArray()) {
            return failImmediately("'steps' array parameter required");
        }

        JsonArray stepsArray = params.getAsJsonArray("steps");
        steps = new ArrayList<>();
        for (JsonElement el : stepsArray) {
            if (!el.isJsonObject()) {
                return failImmediately("Each step must be a JSON object");
            }
            JsonObject step = el.getAsJsonObject();
            if (!step.has("action")) {
                return failImmediately("Each step must have an 'action' field");
            }
            steps.add(step);
        }

        if (steps.isEmpty()) {
            return failImmediately("Steps array is empty");
        }

        currentStepIndex = 0;
        results = new JsonArray();
        currentSubAction = null;
        active = true;

        return future;
    }

    @Override
    public void tick(ServerPlayer agent) {
        if (!active || future.isDone()) {
            active = false;
            return;
        }

        // If we have an active async sub-action, tick it
        if (currentSubAction != null) {
            if (currentSubAction.isActive()) {
                currentSubAction.tick(agent);
                return;
            }
            // Sub-action finished — result should already be captured via whenComplete
            currentSubAction = null;
            // Fall through to process next step
        }

        // Process next step(s)
        processNextStep(agent);
    }

    private void processNextStep(ServerPlayer agent) {
        if (currentStepIndex >= steps.size()) {
            finishSequence(true, null);
            return;
        }

        JsonObject stepDef = steps.get(currentStepIndex);
        String actionName = stepDef.get("action").getAsString();

        Action action = ActionRegistry.getInstance().get(actionName);
        if (action == null) {
            failStep(currentStepIndex, actionName, "Unknown action: " + actionName);
            return;
        }

        LOGGER.debug("Sequence step {}/{}: {}", currentStepIndex + 1, steps.size(), actionName);
        AgentLogger.getInstance().logSubStep("execute_sequence", currentStepIndex,
            "starting step " + (currentStepIndex + 1) + "/" + steps.size() + ": " + actionName + " params=" + stepDef.toString(),
            true, null);

        if (action instanceof AsyncAction) {
            // Create a fresh instance via registry factory (agent isolation)
            AsyncAction freshAction = ActionRegistry.getInstance().createAsync(actionName);
            if (freshAction == null) {
                failStep(currentStepIndex, actionName, "Failed to create action instance");
                return;
            }

            currentSubAction = freshAction;
            CompletableFuture<JsonObject> subFuture = freshAction.start(agent, stepDef);
            subFuture.whenComplete((result, ex) -> {
                if (ex != null) {
                    // Will be handled on next tick
                    results.add(errorResult(ex.getMessage()));
                    failStep(currentStepIndex, actionName, ex.getMessage());
                } else {
                    boolean ok = result.has("ok") && result.get("ok").getAsBoolean();
                    results.add(result);
                    if (!ok) {
                        String error = result.has("error") ? result.get("error").getAsString() : "Action failed";
                        failStep(currentStepIndex, actionName, error);
                    } else {
                        currentStepIndex++;
                        // Next step will be processed on the next tick
                    }
                }
            });
        } else {
            // Sync action — execute immediately
            try {
                JsonObject result = action.execute(agent, stepDef);
                results.add(result);

                boolean ok = result.has("ok") && result.get("ok").getAsBoolean();
                if (!ok) {
                    String error = result.has("error") ? result.get("error").getAsString() : "Action failed";
                    failStep(currentStepIndex, actionName, error);
                    return;
                }

                currentStepIndex++;
                // Process next sync step immediately (same tick) for efficiency
                if (currentStepIndex < steps.size()) {
                    JsonObject nextStep = steps.get(currentStepIndex);
                    String nextAction = nextStep.get("action").getAsString();
                    Action next = ActionRegistry.getInstance().get(nextAction);
                    if (next != null && !(next instanceof AsyncAction)) {
                        processNextStep(agent); // Recurse for sync chains
                    }
                } else {
                    finishSequence(true, null);
                }
            } catch (Exception e) {
                results.add(errorResult(e.getMessage()));
                failStep(currentStepIndex, actionName, e.getMessage());
            }
        }
    }

    private void failStep(int index, String actionName, String error) {
        AgentLogger.getInstance().logSubStep("execute_sequence", index,
            "FAILED step " + (index + 1) + "/" + steps.size() + ": " + actionName,
            false, error);
        JsonObject failedStep = new JsonObject();
        failedStep.addProperty("index", index);
        failedStep.addProperty("action", actionName);
        failedStep.addProperty("error", error);
        finishSequence(false, failedStep);
    }

    private void finishSequence(boolean ok, JsonObject failedStep) {
        active = false;
        currentSubAction = null;

        JsonObject result = new JsonObject();
        result.addProperty("ok", ok);
        result.addProperty("completed_steps", ok ? steps.size() : currentStepIndex);
        result.addProperty("total_steps", steps.size());
        result.add("results", results);
        if (failedStep != null) {
            result.add("failed_step", failedStep);
        }

        future.complete(result);
    }

    @Override
    public void cancel() {
        if (active) {
            active = false;
            if (currentSubAction != null && currentSubAction.isActive()) {
                currentSubAction.cancel();
            }
            currentSubAction = null;
            if (future != null && !future.isDone()) {
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("error", "Sequence cancelled");
                result.addProperty("completed_steps", currentStepIndex);
                result.addProperty("total_steps", steps != null ? steps.size() : 0);
                result.add("results", results != null ? results : new JsonArray());
                future.complete(result);
            }
        }
    }

    @Override
    public boolean isActive() { return active; }

    private CompletableFuture<JsonObject> failImmediately(String error) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", error);
        future.complete(result);
        return future;
    }

    private JsonObject errorResult(String error) {
        JsonObject r = new JsonObject();
        r.addProperty("ok", false);
        r.addProperty("error", error);
        return r;
    }
}
