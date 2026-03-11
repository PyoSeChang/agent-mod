package com.pyosechang.agent.core;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AgentMemoryPath {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Path worldAgentDir;

    public static void initialize(MinecraftServer server) {
        Path worldDir = server.getWorldPath(LevelResource.ROOT);
        worldAgentDir = worldDir.resolve(".agent/agents/default");
        try {
            Files.createDirectories(worldAgentDir);
            // Create default files if they don't exist
            createIfMissing(worldAgentDir.resolve("locations.json"), "[]");
            createIfMissing(worldAgentDir.resolve("preferences.json"), "{}");
            createIfMissing(worldAgentDir.resolve("facilities.json"), "[]");
            createIfMissing(worldAgentDir.resolve("task-history.json"), "[]");
            LOGGER.info("Agent memory initialized at {}", worldAgentDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create agent memory directory", e);
        }
    }

    private static void createIfMissing(Path file, String defaultContent) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, defaultContent);
        }
    }

    public static Path getWorldAgentDir() { return worldAgentDir; }
    public static Path getLocationsFile() { return worldAgentDir.resolve("locations.json"); }
    public static Path getPreferencesFile() { return worldAgentDir.resolve("preferences.json"); }
    public static Path getFacilitiesFile() { return worldAgentDir.resolve("facilities.json"); }
    public static Path getTaskHistoryFile() { return worldAgentDir.resolve("task-history.json"); }
}
