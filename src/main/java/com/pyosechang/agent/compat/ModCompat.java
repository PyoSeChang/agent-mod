package com.pyosechang.agent.compat;

import com.google.gson.JsonObject;
import com.pyosechang.agent.core.action.Action;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public interface ModCompat {
    String getModId();
    List<Action> getActions();
    void extendObservation(JsonObject obs, ServerPlayer agent);
}
