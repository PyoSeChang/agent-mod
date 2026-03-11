package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraftforge.common.util.FakePlayer;

public interface Action {
    String getName();
    JsonObject execute(FakePlayer agent, JsonObject params);
}
