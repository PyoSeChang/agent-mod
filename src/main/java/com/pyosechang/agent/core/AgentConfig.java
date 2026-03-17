package com.pyosechang.agent.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Per-agent game mechanic configuration. Persisted as .agent/agents/{name}/config.json.
 * Separate from PersonaConfig (AI behavior) — this controls game mechanics.
 */
public class AgentConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Gamemode { SURVIVAL, CREATIVE, HARDCORE }

    private Gamemode gamemode = Gamemode.SURVIVAL;

    // Bed location (null = not set)
    private Integer bedX;
    private Integer bedY;
    private Integer bedZ;
    private String bedDimension;

    public AgentConfig() {}

    // --- Gamemode ---

    public Gamemode getGamemode() { return gamemode; }
    public void setGamemode(Gamemode gamemode) { this.gamemode = gamemode; }

    // --- Bed ---

    public boolean hasBed() { return bedX != null && bedY != null && bedZ != null; }
    public Integer getBedX() { return bedX; }
    public Integer getBedY() { return bedY; }
    public Integer getBedZ() { return bedZ; }
    public String getBedDimension() { return bedDimension; }

    public void setBed(int x, int y, int z, String dimension) {
        this.bedX = x;
        this.bedY = y;
        this.bedZ = z;
        this.bedDimension = dimension;
    }

    public void clearBed() {
        this.bedX = null;
        this.bedY = null;
        this.bedZ = null;
        this.bedDimension = null;
    }

    // --- Persistence ---

    private static Path getConfigPath(String name) {
        return FMLPaths.GAMEDIR.get().resolve(".agent/agents/" + name + "/config.json");
    }

    public static AgentConfig load(String name) {
        Path path = getConfigPath(name);
        if (!Files.exists(path)) return new AgentConfig();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            AgentConfig config = GSON.fromJson(reader, AgentConfig.class);
            return config != null ? config : new AgentConfig();
        } catch (Exception e) {
            LOGGER.error("Failed to load config for '{}', using defaults", name, e);
            return new AgentConfig();
        }
    }

    public void save(String name) {
        Path path = getConfigPath(name);
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("Failed to save config for '{}'", name, e);
        }
    }

    // --- JSON conversion (for HTTP transport) ---

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("gamemode", gamemode.name());
        if (hasBed()) {
            JsonObject bed = new JsonObject();
            bed.addProperty("x", bedX);
            bed.addProperty("y", bedY);
            bed.addProperty("z", bedZ);
            bed.addProperty("dimension", bedDimension);
            obj.add("bed", bed);
        }
        return obj;
    }

    public static AgentConfig fromJson(JsonObject json) {
        AgentConfig config = new AgentConfig();
        if (json.has("gamemode")) {
            try {
                config.gamemode = Gamemode.valueOf(json.get("gamemode").getAsString());
            } catch (IllegalArgumentException ignored) {}
        }
        if (json.has("bed") && !json.get("bed").isJsonNull()) {
            JsonObject bed = json.getAsJsonObject("bed");
            config.setBed(
                bed.get("x").getAsInt(),
                bed.get("y").getAsInt(),
                bed.get("z").getAsInt(),
                bed.has("dimension") ? bed.get("dimension").getAsString() : "minecraft:overworld"
            );
        }
        return config;
    }
}
