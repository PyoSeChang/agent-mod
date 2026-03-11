package com.pyosechang.agent.core.action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ActionRegistry {
    private static final ActionRegistry INSTANCE = new ActionRegistry();
    private final Map<String, Action> actions = new LinkedHashMap<>();

    public static ActionRegistry getInstance() { return INSTANCE; }

    public void register(Action action) { actions.put(action.getName(), action); }
    public Action get(String name) { return actions.get(name); }
    public List<String> listNames() { return new ArrayList<>(actions.keySet()); }
}
