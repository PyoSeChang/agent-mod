package com.pyosechang.agent.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PersonaConfig} -- focused on the package-private
 * {@code parseContent(String, String)} method and public accessors.
 */
class PersonaConfigTest {

    // -- helpers ----------------------------------------------------------

    private static PersonaConfig parse(String content) {
        return PersonaConfig.parseContent("test-agent", content);
    }

    // -- parseContent tests -----------------------------------------------

    @Nested
    @DisplayName("parseContent")
    class ParseContent {

        @Test
        @DisplayName("1. Complete PERSONA.md with all sections")
        void completePersona() {
            String md = """
                    ## Role
                    Farmer who tends crops

                    ## Personality
                    Calm and methodical

                    ## Tools
                    - move_to
                    - mine_block

                    ## Acquaintances
                    - alex: Fellow farmer who handles animals
                    - steve: Builder of the village
                    """;

            PersonaConfig cfg = parse(md);

            assertEquals("test-agent", cfg.getName());
            assertEquals("Farmer who tends crops", cfg.getRole());
            assertEquals("Calm and methodical", cfg.getPersonality());
            assertEquals(List.of("move_to", "mine_block"), cfg.getTools());
            assertFalse(cfg.isAllToolsAllowed());

            assertEquals(2, cfg.getAcquaintances().size());
            assertEquals("alex", cfg.getAcquaintances().get(0).name());
            assertEquals("Fellow farmer who handles animals", cfg.getAcquaintances().get(0).description());
            assertEquals("steve", cfg.getAcquaintances().get(1).name());
            assertEquals("Builder of the village", cfg.getAcquaintances().get(1).description());
        }

        @Test
        @DisplayName("2. Empty tools section -> isAllToolsAllowed")
        void emptyToolsSection() {
            String md = """
                    ## Role
                    General helper

                    ## Tools
                    """;

            PersonaConfig cfg = parse(md);

            assertTrue(cfg.getTools().isEmpty());
            assertTrue(cfg.isAllToolsAllowed());
        }

        @Test
        @DisplayName("3. Missing sections -> only provided section set")
        void missingSections() {
            String md = """
                    ## Role
                    Solo miner
                    """;

            PersonaConfig cfg = parse(md);

            assertEquals("Solo miner", cfg.getRole());
            assertEquals("", cfg.getPersonality());
            assertTrue(cfg.getTools().isEmpty());
            assertTrue(cfg.getAcquaintances().isEmpty());
        }

        @Test
        @DisplayName("4. Empty content -> all fields empty/default")
        void emptyContent() {
            PersonaConfig cfg = parse("");

            assertEquals("test-agent", cfg.getName());
            assertEquals("", cfg.getRole());
            assertEquals("", cfg.getPersonality());
            assertTrue(cfg.getTools().isEmpty());
            assertTrue(cfg.getAcquaintances().isEmpty());
        }

        @Test
        @DisplayName("5. Reversed section order -> all sections parsed correctly")
        void reversedSectionOrder() {
            String md = """
                    ## Acquaintances
                    - bob: A friend

                    ## Tools
                    - craft
                    - equip

                    ## Personality
                    Energetic and creative

                    ## Role
                    Builder
                    """;

            PersonaConfig cfg = parse(md);

            assertEquals("Builder", cfg.getRole());
            assertEquals("Energetic and creative", cfg.getPersonality());
            assertEquals(List.of("craft", "equip"), cfg.getTools());
            assertEquals(1, cfg.getAcquaintances().size());
            assertEquals("bob", cfg.getAcquaintances().get(0).name());
        }

        @Test
        @DisplayName("6. Acquaintance with colon in description -> first colon splits")
        void acquaintanceColonInDescription() {
            String md = """
                    ## Acquaintances
                    - alex: Farmer note: very skilled at wheat
                    """;

            PersonaConfig cfg = parse(md);

            assertEquals(1, cfg.getAcquaintances().size());
            assertEquals("alex", cfg.getAcquaintances().get(0).name());
            assertEquals("Farmer note: very skilled at wheat",
                    cfg.getAcquaintances().get(0).description());
        }

        @Test
        @DisplayName("7. Acquaintance without description -> name only, empty description")
        void acquaintanceNoDescription() {
            String md = """
                    ## Acquaintances
                    - mysterious_stranger
                    """;

            PersonaConfig cfg = parse(md);

            assertEquals(1, cfg.getAcquaintances().size());
            assertEquals("mysterious_stranger", cfg.getAcquaintances().get(0).name());
            assertEquals("", cfg.getAcquaintances().get(0).description());
        }

        @Test
        @DisplayName("8. Tools with asterisk bullet style")
        void toolsAsteriskBullet() {
            String md = """
                    ## Tools
                    * move_to
                    * mine_block
                    - craft
                    """;

            PersonaConfig cfg = parse(md);

            assertEquals(List.of("move_to", "mine_block", "craft"), cfg.getTools());
        }

        @Test
        @DisplayName("9. Section header case -> lowercase matching")
        void sectionHeaderCase() {
            // The parser lowercases the header text, so mixed case should work
            String md = """
                    ## ROLE
                    Uppercase role

                    ## Personality
                    Normal case
                    """;

            PersonaConfig cfg = parse(md);

            // "ROLE" lowercased = "role" -> should match
            assertEquals("Uppercase role", cfg.getRole());
            assertEquals("Normal case", cfg.getPersonality());
        }

        @Test
        @DisplayName("13. Multiline role content -> preserves newlines within section")
        void multilineRole() {
            String md = """
                    ## Role
                    Primary: Farmer
                    Secondary: Builder
                    Notes: Can also do mining

                    ## Personality
                    Friendly
                    """;

            PersonaConfig cfg = parse(md);

            // The section content should preserve internal newlines
            assertTrue(cfg.getRole().contains("Primary: Farmer"));
            assertTrue(cfg.getRole().contains("Secondary: Builder"));
            assertTrue(cfg.getRole().contains("Notes: Can also do mining"));
        }

        @Test
        @DisplayName("14. Tools with extra whitespace -> trimmed")
        void toolsExtraWhitespace() {
            String md = """
                    ## Tools
                    -   move_to  \s
                    -  mine_block
                    """;

            PersonaConfig cfg = parse(md);

            assertEquals(List.of("move_to", "mine_block"), cfg.getTools());
        }

        @Test
        @DisplayName("15. Non-h2 headers ignored -> only ## triggers section split")
        void nonH2HeadersIgnored() {
            String md = """
                    ## Role
                    # This is an H1, not a section marker
                    Main farmer role
                    ### This is H3, also not a section marker

                    ## Personality
                    Calm
                    """;

            PersonaConfig cfg = parse(md);

            // H1 and H3 lines should be part of the Role content, not trigger splits
            assertTrue(cfg.getRole().contains("# This is an H1, not a section marker"));
            assertTrue(cfg.getRole().contains("Main farmer role"));
            assertTrue(cfg.getRole().contains("### This is H3, also not a section marker"));
            assertEquals("Calm", cfg.getPersonality());
        }
    }

    // -- defaultPersona tests -----------------------------------------------

    @Nested
    @DisplayName("defaultPersona")
    class DefaultPersona {

        @Test
        @DisplayName("10. defaultPersona returns correct defaults")
        void defaultPersonaValues() {
            PersonaConfig cfg = PersonaConfig.defaultPersona("farmer");

            assertEquals("farmer", cfg.getName());
            assertEquals("General-purpose agent", cfg.getRole());
            assertEquals("Helpful and efficient.", cfg.getPersonality());
            assertTrue(cfg.getTools().isEmpty());
            assertTrue(cfg.isAllToolsAllowed());
            assertTrue(cfg.getAcquaintances().isEmpty());
            assertNotNull(cfg.getRawContent());
        }
    }

    // -- getToolsCsv tests ---------------------------------------------------

    @Nested
    @DisplayName("getToolsCsv")
    class GetToolsCsv {

        @Test
        @DisplayName("11. getToolsCsv with tools -> comma-separated string")
        void toolsCsvWithTools() {
            String md = """
                    ## Tools
                    - move_to
                    - mine_block
                    - craft
                    """;

            PersonaConfig cfg = parse(md);

            assertEquals("move_to,mine_block,craft", cfg.getToolsCsv());
        }

        @Test
        @DisplayName("12. getToolsCsv with no tools -> empty string")
        void toolsCsvEmpty() {
            PersonaConfig cfg = parse("");

            assertEquals("", cfg.getToolsCsv());
        }
    }
}
