package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

public class CloseContainerAction implements Action {
    @Override
    public String getName() { return "close_container"; }

    @Override
    public JsonObject execute(ServerPlayer agent, JsonObject params) {
        if (agent.containerMenu != agent.inventoryMenu) {
            agent.closeContainer();
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }
}
