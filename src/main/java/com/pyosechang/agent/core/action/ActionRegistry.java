package com.pyosechang.agent.core.action;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for actions. Sync actions are stored as shared instances (stateless).
 * Async actions are stored as class references and instantiated fresh per execution.
 */
public class ActionRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ActionRegistry INSTANCE = new ActionRegistry();

    private final Map<String, Action> syncActions = new LinkedHashMap<>();
    private final Map<String, Class<? extends AsyncAction>> asyncFactories = new LinkedHashMap<>();
    private final List<String> allNames = new ArrayList<>();

    public static ActionRegistry getInstance() { return INSTANCE; }

    /**
     * Register a sync action (stateless, shared instance).
     */
    public void register(Action action) {
        syncActions.put(action.getName(), action);
        if (!allNames.contains(action.getName())) {
            allNames.add(action.getName());
        }
    }

    /**
     * Register an async action class (stateful, fresh instance per execution).
     */
    public void registerAsync(Class<? extends AsyncAction> actionClass) {
        try {
            AsyncAction template = actionClass.getDeclaredConstructor().newInstance();
            String name = template.getName();
            asyncFactories.put(name, actionClass);
            if (!allNames.contains(name)) {
                allNames.add(name);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to register async action class: {}", actionClass.getSimpleName(), e);
        }
    }

    /**
     * Get a sync action instance. Returns null if not found or if it's async.
     */
    public Action get(String name) {
        Action sync = syncActions.get(name);
        if (sync != null) return sync;
        // For async actions, create a fresh instance
        return createAsync(name);
    }

    /**
     * Create a fresh async action instance. Returns null if not found.
     */
    public AsyncAction createAsync(String name) {
        Class<? extends AsyncAction> clazz = asyncFactories.get(name);
        if (clazz == null) return null;
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            LOGGER.error("Failed to create async action '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Check if the given action name is registered as async.
     */
    public boolean isAsync(String name) {
        return asyncFactories.containsKey(name);
    }

    public List<String> listNames() { return new ArrayList<>(allNames); }
}
