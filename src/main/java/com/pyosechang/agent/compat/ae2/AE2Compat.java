package com.pyosechang.agent.compat.ae2;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.compat.ModCompat;
import com.pyosechang.agent.core.action.Action;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * AE2 compatibility layer. Requires Applied Energistics 2 to be installed.
 * Actions: ae2_search, ae2_request_craft, ae2_export
 *
 * Note: Actual AE2 API calls require AE2 as compileOnly dependency.
 * This stub provides the framework; real implementation needs AE2 on classpath.
 */
public class AE2Compat implements ModCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public String getModId() { return "ae2"; }

    @Override
    public List<Action> getActions() {
        List<Action> actions = new ArrayList<>();
        // TODO: Implement when AE2 dependency is added
        // actions.add(new AE2SearchAction());
        // actions.add(new AE2RequestCraftAction());
        // actions.add(new AE2ExportAction());
        LOGGER.info("AE2 compat registered (stub - actions pending AE2 API integration)");
        return actions;
    }

    @Override
    public void extendObservation(JsonObject obs, ServerPlayer agent) {
        // TODO: Query AE2 network status
        // JsonObject ae2 = new JsonObject();
        // ae2.addProperty("channels_used", ...);
        // ae2.addProperty("channels_max", ...);
        // ae2.addProperty("items_stored", ...);
        // obs.add("ae2_network", ae2);
    }
}
