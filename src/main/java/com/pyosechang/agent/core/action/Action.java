package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

public interface Action {
    String getName();
    JsonObject execute(ServerPlayer agent, JsonObject params);
}
