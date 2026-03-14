package com.pyosechang.agent.core.schedule;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.minecraft.core.BlockPos;

/**
 * Individual observer definition — a position + event type + optional condition.
 */
public class ObserverDef {

    private int x, y, z;
    @SerializedName(value = "event", alternate = {"eventType"})
    private String eventType;    // e.g. "crop_grow", "block_break"
    private String condition;    // e.g. "age=7", "type=zombie" (nullable)

    public ObserverDef() {}

    public ObserverDef(int x, int y, int z, String eventType, String condition) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.eventType = eventType;
        this.condition = condition;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public String getEventType() { return eventType; }
    public String getCondition() { return condition; }

    public BlockPos getBlockPos() { return new BlockPos(x, y, z); }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        obj.addProperty("event", eventType);
        if (condition != null && !condition.isEmpty()) {
            obj.addProperty("condition", condition);
        }
        return obj;
    }

    public static ObserverDef fromJson(JsonObject obj) {
        ObserverDef def = new ObserverDef();
        def.x = obj.get("x").getAsInt();
        def.y = obj.get("y").getAsInt();
        def.z = obj.get("z").getAsInt();
        def.eventType = obj.has("event") ? obj.get("event").getAsString() : "";
        def.condition = obj.has("condition") ? obj.get("condition").getAsString() : null;
        return def;
    }
}
