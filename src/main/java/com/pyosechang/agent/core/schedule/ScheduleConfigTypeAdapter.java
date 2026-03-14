package com.pyosechang.agent.core.schedule;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import java.lang.reflect.Type;

/**
 * Gson TypeAdapter for ScheduleConfig — uses the existing manual toJson/fromJson methods
 * to ensure consistent serialization (especially for ObserverDef "event" key).
 */
public class ScheduleConfigTypeAdapter implements JsonSerializer<ScheduleConfig>, JsonDeserializer<ScheduleConfig> {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public JsonElement serialize(ScheduleConfig src, Type typeOfSrc, JsonSerializationContext context) {
        return src.toJson();
    }

    @Override
    public ScheduleConfig deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (!json.isJsonObject()) throw new JsonParseException("ScheduleConfig must be a JSON object");
        JsonObject obj = json.getAsJsonObject();
        LOGGER.info("ScheduleConfigTypeAdapter.deserialize called, type={}", obj.has("type") ? obj.get("type").getAsString() : "null");
        if (obj.has("observers") && obj.get("observers").isJsonArray() && !obj.getAsJsonArray("observers").isEmpty()) {
            JsonObject firstObs = obj.getAsJsonArray("observers").get(0).getAsJsonObject();
            LOGGER.info("  first observer keys: {}, event={}", firstObs.keySet(), firstObs.has("event") ? firstObs.get("event").getAsString() : "MISSING");
        }
        ScheduleConfig config = ScheduleConfig.fromJson(obj);
        if (config.getObservers() != null && !config.getObservers().isEmpty()) {
            LOGGER.info("  parsed eventType={}", config.getObservers().get(0).getEventType());
        }
        return config;
    }
}
