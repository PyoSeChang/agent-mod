package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import com.pyosechang.agent.core.FakePlayerManager;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;

public class MoveToAction implements Action {
    @Override
    public String getName() { return "move_to"; }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        double x = params.get("x").getAsDouble();
        double y = params.get("y").getAsDouble();
        double z = params.get("z").getAsDouble();

        // Set position directly (no +0.5 — caller provides exact coords)
        agent.setPos(x, y, z);

        // Broadcast position update to all clients
        ClientboundTeleportEntityPacket packet = new ClientboundTeleportEntityPacket(agent);
        var server = FakePlayerManager.getInstance().getServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.connection.send(packet);
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        JsonObject pos = new JsonObject();
        pos.addProperty("x", agent.getX());
        pos.addProperty("y", agent.getY());
        pos.addProperty("z", agent.getZ());
        result.add("position", pos);
        return result;
    }
}
