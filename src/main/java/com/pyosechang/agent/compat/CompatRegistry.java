package com.pyosechang.agent.compat;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.pyosechang.agent.core.action.Action;
import com.pyosechang.agent.core.action.ActionRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class CompatRegistry {
    private static final CompatRegistry INSTANCE = new CompatRegistry();
    private static final Logger LOGGER = LogUtils.getLogger();
    private final List<ModCompat> compats = new ArrayList<>();

    public static CompatRegistry getInstance() { return INSTANCE; }

    public void register(ModCompat compat) {
        if (ModList.get().isLoaded(compat.getModId())) {
            compats.add(compat);
            for (Action action : compat.getActions()) {
                ActionRegistry.getInstance().register(action);
            }
            LOGGER.info("Registered mod compat: {} ({} actions)",
                compat.getModId(), compat.getActions().size());
        }
    }

    public void extendObservation(JsonObject obs, ServerPlayer agent) {
        JsonObject modData = new JsonObject();
        for (ModCompat compat : compats) {
            try {
                compat.extendObservation(modData, agent);
            } catch (Exception e) {
                LOGGER.warn("Mod compat observation failed for {}: {}",
                    compat.getModId(), e.getMessage());
            }
        }
        if (modData.size() > 0) {
            obs.add("mod_data", modData);
        }
    }

    public List<ModCompat> getLoadedCompats() { return new ArrayList<>(compats); }
}
