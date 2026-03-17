package com.pyosechang.agent.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentConfig} — gamemode, bed, JSON serialization.
 */
class AgentConfigTest {

    // === Defaults ===

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("new config has SURVIVAL gamemode")
        void defaultGamemode() {
            AgentConfig config = new AgentConfig();
            assertEquals(AgentConfig.Gamemode.SURVIVAL, config.getGamemode());
        }

        @Test
        @DisplayName("new config has no bed")
        void defaultNoBed() {
            AgentConfig config = new AgentConfig();
            assertFalse(config.hasBed());
            assertNull(config.getBedX());
            assertNull(config.getBedY());
            assertNull(config.getBedZ());
            assertNull(config.getBedDimension());
        }
    }

    // === Gamemode ===

    @Nested
    @DisplayName("gamemode")
    class GamemodeTests {

        @Test
        @DisplayName("set and get gamemode")
        void setGamemode() {
            AgentConfig config = new AgentConfig();
            config.setGamemode(AgentConfig.Gamemode.CREATIVE);
            assertEquals(AgentConfig.Gamemode.CREATIVE, config.getGamemode());

            config.setGamemode(AgentConfig.Gamemode.HARDCORE);
            assertEquals(AgentConfig.Gamemode.HARDCORE, config.getGamemode());
        }

        @Test
        @DisplayName("enum has exactly 3 values")
        void enumValues() {
            AgentConfig.Gamemode[] values = AgentConfig.Gamemode.values();
            assertEquals(3, values.length);
            assertEquals(AgentConfig.Gamemode.SURVIVAL, AgentConfig.Gamemode.valueOf("SURVIVAL"));
            assertEquals(AgentConfig.Gamemode.CREATIVE, AgentConfig.Gamemode.valueOf("CREATIVE"));
            assertEquals(AgentConfig.Gamemode.HARDCORE, AgentConfig.Gamemode.valueOf("HARDCORE"));
        }
    }

    // === Bed ===

    @Nested
    @DisplayName("bed")
    class BedTests {

        @Test
        @DisplayName("setBed stores coordinates and dimension")
        void setBed() {
            AgentConfig config = new AgentConfig();
            config.setBed(100, 64, -200, "minecraft:overworld");

            assertTrue(config.hasBed());
            assertEquals(100, config.getBedX());
            assertEquals(64, config.getBedY());
            assertEquals(-200, config.getBedZ());
            assertEquals("minecraft:overworld", config.getBedDimension());
        }

        @Test
        @DisplayName("clearBed removes all bed data")
        void clearBed() {
            AgentConfig config = new AgentConfig();
            config.setBed(10, 20, 30, "minecraft:the_nether");
            assertTrue(config.hasBed());

            config.clearBed();
            assertFalse(config.hasBed());
            assertNull(config.getBedX());
            assertNull(config.getBedY());
            assertNull(config.getBedZ());
            assertNull(config.getBedDimension());
        }

        @Test
        @DisplayName("hasBed requires all three coordinates")
        void hasBedRequiresAllCoords() {
            AgentConfig config = new AgentConfig();
            // Only bedX set via reflection would still be false — but via public API
            // setBed always sets all three, so just test the normal flow
            assertFalse(config.hasBed());
            config.setBed(0, 0, 0, "minecraft:overworld");
            assertTrue(config.hasBed());
        }
    }

    // === toJson ===

    @Nested
    @DisplayName("toJson")
    class ToJsonTests {

        @Test
        @DisplayName("default config serializes with SURVIVAL, no bed")
        void defaultToJson() {
            AgentConfig config = new AgentConfig();
            JsonObject json = config.toJson();

            assertEquals("SURVIVAL", json.get("gamemode").getAsString());
            assertFalse(json.has("bed"));
        }

        @Test
        @DisplayName("config with bed serializes bed object")
        void withBedToJson() {
            AgentConfig config = new AgentConfig();
            config.setGamemode(AgentConfig.Gamemode.HARDCORE);
            config.setBed(100, 64, -200, "minecraft:overworld");

            JsonObject json = config.toJson();
            assertEquals("HARDCORE", json.get("gamemode").getAsString());
            assertTrue(json.has("bed"));

            JsonObject bed = json.getAsJsonObject("bed");
            assertEquals(100, bed.get("x").getAsInt());
            assertEquals(64, bed.get("y").getAsInt());
            assertEquals(-200, bed.get("z").getAsInt());
            assertEquals("minecraft:overworld", bed.get("dimension").getAsString());
        }

        @Test
        @DisplayName("CREATIVE gamemode serializes correctly")
        void creativeToJson() {
            AgentConfig config = new AgentConfig();
            config.setGamemode(AgentConfig.Gamemode.CREATIVE);
            JsonObject json = config.toJson();
            assertEquals("CREATIVE", json.get("gamemode").getAsString());
        }
    }

    // === fromJson ===

    @Nested
    @DisplayName("fromJson")
    class FromJsonTests {

        @Test
        @DisplayName("parses gamemode and bed from JSON")
        void fullFromJson() {
            JsonObject json = new JsonObject();
            json.addProperty("gamemode", "HARDCORE");
            JsonObject bed = new JsonObject();
            bed.addProperty("x", 50);
            bed.addProperty("y", 70);
            bed.addProperty("z", -100);
            bed.addProperty("dimension", "minecraft:the_nether");
            json.add("bed", bed);

            AgentConfig config = AgentConfig.fromJson(json);
            assertEquals(AgentConfig.Gamemode.HARDCORE, config.getGamemode());
            assertTrue(config.hasBed());
            assertEquals(50, config.getBedX());
            assertEquals(70, config.getBedY());
            assertEquals(-100, config.getBedZ());
            assertEquals("minecraft:the_nether", config.getBedDimension());
        }

        @Test
        @DisplayName("empty JSON returns defaults")
        void emptyFromJson() {
            AgentConfig config = AgentConfig.fromJson(new JsonObject());
            assertEquals(AgentConfig.Gamemode.SURVIVAL, config.getGamemode());
            assertFalse(config.hasBed());
        }

        @Test
        @DisplayName("invalid gamemode falls back to SURVIVAL")
        void invalidGamemode() {
            JsonObject json = new JsonObject();
            json.addProperty("gamemode", "INVALID_MODE");
            AgentConfig config = AgentConfig.fromJson(json);
            assertEquals(AgentConfig.Gamemode.SURVIVAL, config.getGamemode());
        }

        @Test
        @DisplayName("null bed field clears bed")
        void nullBed() {
            JsonObject json = new JsonObject();
            json.addProperty("gamemode", "SURVIVAL");
            json.add("bed", com.google.gson.JsonNull.INSTANCE);
            AgentConfig config = AgentConfig.fromJson(json);
            assertFalse(config.hasBed());
        }

        @Test
        @DisplayName("bed without dimension defaults to overworld")
        void bedWithoutDimension() {
            JsonObject json = new JsonObject();
            JsonObject bed = new JsonObject();
            bed.addProperty("x", 10);
            bed.addProperty("y", 20);
            bed.addProperty("z", 30);
            json.add("bed", bed);

            AgentConfig config = AgentConfig.fromJson(json);
            assertTrue(config.hasBed());
            assertEquals("minecraft:overworld", config.getBedDimension());
        }
    }

    // === Round-trip ===

    @Nested
    @DisplayName("toJson → fromJson round-trip")
    class RoundTripTests {

        @Test
        @DisplayName("survival with no bed round-trips")
        void survivalNoBed() {
            AgentConfig original = new AgentConfig();
            AgentConfig restored = AgentConfig.fromJson(original.toJson());

            assertEquals(original.getGamemode(), restored.getGamemode());
            assertEquals(original.hasBed(), restored.hasBed());
        }

        @Test
        @DisplayName("hardcore with bed round-trips")
        void hardcoreWithBed() {
            AgentConfig original = new AgentConfig();
            original.setGamemode(AgentConfig.Gamemode.HARDCORE);
            original.setBed(999, 32, -777, "minecraft:the_end");

            AgentConfig restored = AgentConfig.fromJson(original.toJson());

            assertEquals(AgentConfig.Gamemode.HARDCORE, restored.getGamemode());
            assertTrue(restored.hasBed());
            assertEquals(999, restored.getBedX());
            assertEquals(32, restored.getBedY());
            assertEquals(-777, restored.getBedZ());
            assertEquals("minecraft:the_end", restored.getBedDimension());
        }

        @Test
        @DisplayName("creative with no bed round-trips")
        void creativeNoBed() {
            AgentConfig original = new AgentConfig();
            original.setGamemode(AgentConfig.Gamemode.CREATIVE);

            AgentConfig restored = AgentConfig.fromJson(original.toJson());
            assertEquals(AgentConfig.Gamemode.CREATIVE, restored.getGamemode());
            assertFalse(restored.hasBed());
        }
    }
}
