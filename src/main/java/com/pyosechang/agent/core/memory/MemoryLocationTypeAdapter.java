package com.pyosechang.agent.core.memory;

import com.google.gson.*;
import java.lang.reflect.Type;

/**
 * Gson TypeAdapter for MemoryLocation interface — dispatches on "type" field.
 * Handles backward compat: old point with radius, old area with single y.
 */
public class MemoryLocationTypeAdapter implements JsonSerializer<MemoryLocation>, JsonDeserializer<MemoryLocation> {

    private static final Gson INTERNAL = new Gson();

    @Override
    public JsonElement serialize(MemoryLocation src, Type typeOfSrc, JsonSerializationContext context) {
        return INTERNAL.toJsonTree(src);
    }

    @Override
    public MemoryLocation deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (!json.isJsonObject()) throw new JsonParseException("MemoryLocation must be a JSON object");
        JsonObject obj = json.getAsJsonObject();
        String type = obj.has("type") ? obj.get("type").getAsString() : "point";

        if ("area".equals(type)) {
            double x1 = obj.has("x1") ? obj.get("x1").getAsDouble() : 0;
            double z1 = obj.has("z1") ? obj.get("z1").getAsDouble() : 0;
            double x2 = obj.has("x2") ? obj.get("x2").getAsDouble() : 0;
            double z2 = obj.has("z2") ? obj.get("z2").getAsDouble() : 0;
            // New format: y1/y2. Old format: single y
            double y1, y2;
            if (obj.has("y1")) {
                y1 = obj.get("y1").getAsDouble();
                y2 = obj.has("y2") ? obj.get("y2").getAsDouble() : y1;
            } else {
                y1 = obj.has("y") ? obj.get("y").getAsDouble() : 0;
                y2 = y1;
            }
            return new AreaLocation(x1, y1, z1, x2, y2, z2);
        } else {
            double x = obj.has("x") ? obj.get("x").getAsDouble() : 0;
            double y = obj.has("y") ? obj.get("y").getAsDouble() : 0;
            double z = obj.has("z") ? obj.get("z").getAsDouble() : 0;
            // Ignore old radius field
            return new PointLocation(x, y, z);
        }
    }
}
