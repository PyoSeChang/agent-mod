package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

public class AttackAction implements Action {

    @Override
    public String getName() {
        return "attack";
    }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        JsonObject result = new JsonObject();

        int entityId = params.get("entity_id").getAsInt();

        if (!(agent.level() instanceof ServerLevel level)) {
            result.addProperty("ok", false);
            result.addProperty("error", "Not in a server level");
            return result;
        }

        Entity target = level.getEntity(entityId);
        if (target == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "Entity not found with id: " + entityId);
            return result;
        }

        double distance = agent.distanceTo(target);
        if (distance > 6.0) {
            result.addProperty("ok", false);
            result.addProperty("error", "Entity too far away (distance: " + String.format("%.1f", distance) + ")");
            return result;
        }

        // Record health before attack (if living entity)
        float healthBefore = 0;
        if (target instanceof LivingEntity living) {
            healthBefore = living.getHealth();
        }

        agent.attack(target);

        result.addProperty("ok", true);
        String entityType = ForgeRegistries.ENTITY_TYPES.getKey(target.getType()).toString();
        result.addProperty("attacked_entity", entityType);
        result.addProperty("entity_id", entityId);

        if (target instanceof LivingEntity living) {
            float healthAfter = living.getHealth();
            result.addProperty("damage_dealt", healthBefore - healthAfter);
            result.addProperty("target_health", healthAfter);
            result.addProperty("target_alive", living.isAlive());
        }

        return result;
    }
}
