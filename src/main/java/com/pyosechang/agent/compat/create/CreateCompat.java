package com.pyosechang.agent.compat.create;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.compat.ModCompat;
import com.pyosechang.agent.core.action.Action;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Create mod compatibility layer.
 * Actions: create_get_kinetic
 *
 * Note: Actual Create API calls require Create as compileOnly dependency.
 */
public class CreateCompat implements ModCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public String getModId() { return "create"; }

    @Override
    public List<Action> getActions() {
        List<Action> actions = new ArrayList<>();
        // TODO: Implement when Create dependency is added
        // actions.add(new CreateGetKineticAction());
        LOGGER.info("Create compat registered (stub - actions pending Create API integration)");
        return actions;
    }

    @Override
    public void extendObservation(JsonObject obs, ServerPlayer agent) {
        // TODO: Query nearby kinetic network
        // JsonObject create = new JsonObject();
        // create.addProperty("rpm", ...);
        // create.addProperty("stress", ...);
        // create.addProperty("capacity", ...);
        // obs.add("create_kinetic", create);
    }
}
