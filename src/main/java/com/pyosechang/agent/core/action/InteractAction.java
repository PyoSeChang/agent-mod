package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import com.pyosechang.agent.core.AgentAnimation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

public class InteractAction implements Action {

    @Override
    public String getName() {
        return "interact";
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

        // Look at entity
        AgentAnimation.lookAt(agent, target.getX(), target.getY() + target.getEyeHeight() * 0.5, target.getZ());

        InteractionResult interactionResult = agent.interactOn(target, InteractionHand.MAIN_HAND);

        String entityType = ForgeRegistries.ENTITY_TYPES.getKey(target.getType()).toString();
        result.addProperty("ok", true);
        result.addProperty("interacted_entity", entityType);
        result.addProperty("entity_id", entityId);
        result.addProperty("interaction_result", interactionResult.name());
        return result;
    }
}
