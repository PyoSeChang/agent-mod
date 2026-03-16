package com.pyosechang.agent.core.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryEntryTest {

    private MemoryEntry entry;

    @BeforeEach
    void setUp() {
        entry = new MemoryEntry();
    }

    // --- getScope() ---

    @Nested
    @DisplayName("getScope()")
    class GetScope {

        @Test
        @DisplayName("empty visibleTo returns 'global'")
        void emptyVisibleToReturnsGlobal() {
            assertEquals("global", entry.getScope());
        }

        @Test
        @DisplayName("single agent returns 'agent:<name>'")
        void singleAgentScope() {
            entry.setVisibleTo(List.of("alex"));
            assertEquals("agent:alex", entry.getScope());
        }

        @Test
        @DisplayName("multiple agents returns 'agents:<name1>,<name2>'")
        void multipleAgentsScope() {
            entry.setVisibleTo(List.of("alex", "steve"));
            assertEquals("agents:alex,steve", entry.getScope());
        }
    }

    // --- isVisibleTo() ---

    @Nested
    @DisplayName("isVisibleTo()")
    class IsVisibleTo {

        @Test
        @DisplayName("global entry is visible to any agent")
        void globalEntryVisibleToAnyone() {
            assertTrue(entry.isVisibleTo("alex"));
            assertTrue(entry.isVisibleTo("steve"));
        }

        @Test
        @DisplayName("scoped entry visible to listed agent")
        void scopedEntryVisibleToListed() {
            entry.setVisibleTo(List.of("alex"));
            assertTrue(entry.isVisibleTo("alex"));
        }

        @Test
        @DisplayName("scoped entry not visible to unlisted agent")
        void scopedEntryNotVisibleToUnlisted() {
            entry.setVisibleTo(List.of("alex"));
            assertFalse(entry.isVisibleTo("steve"));
        }
    }

    // --- isGlobal() ---

    @Nested
    @DisplayName("isGlobal()")
    class IsGlobal {

        @Test
        @DisplayName("default entry is global")
        void defaultEntryIsGlobal() {
            assertTrue(entry.isGlobal());
        }

        @Test
        @DisplayName("setVisibleTo(null) makes entry global and returns empty list")
        void setVisibleToNullMakesGlobal() {
            entry.setVisibleTo(List.of("alex"));
            assertFalse(entry.isGlobal());

            entry.setVisibleTo(null);
            assertTrue(entry.isGlobal());
            assertNotNull(entry.getVisibleTo());
            assertTrue(entry.getVisibleTo().isEmpty());
        }
    }

    // --- matchesQuery() ---

    @Nested
    @DisplayName("matchesQuery()")
    class MatchesQuery {

        @Test
        @DisplayName("null query matches everything")
        void nullQueryMatchesAll() {
            entry.setTitle("Farm Location");
            assertTrue(entry.matchesQuery(null));
        }

        @Test
        @DisplayName("blank query matches everything")
        void blankQueryMatchesAll() {
            entry.setTitle("Farm Location");
            assertTrue(entry.matchesQuery(""));
            assertTrue(entry.matchesQuery("   "));
        }

        @Test
        @DisplayName("case-insensitive match on title")
        void caseInsensitiveTitle() {
            entry.setTitle("Wheat Farm");
            assertTrue(entry.matchesQuery("wheat"));
            assertTrue(entry.matchesQuery("WHEAT"));
            assertTrue(entry.matchesQuery("Wheat"));
        }

        @Test
        @DisplayName("case-insensitive match on description")
        void caseInsensitiveDescription() {
            entry.setDescription("Located near the river");
            assertTrue(entry.matchesQuery("river"));
            assertTrue(entry.matchesQuery("RIVER"));
        }

        @Test
        @DisplayName("no match returns false")
        void noMatchReturnsFalse() {
            entry.setTitle("Wheat Farm");
            entry.setDescription("Grows wheat");
            assertFalse(entry.matchesQuery("diamond"));
        }

        @Test
        @DisplayName("content field is NOT searched")
        void contentNotSearched() {
            entry.setContent("secret diamond location");
            entry.setTitle("Farm");
            entry.setDescription("Grows wheat");
            assertFalse(entry.matchesQuery("diamond"));
        }

        @Test
        @DisplayName("null title and description returns false for non-empty query")
        void nullTitleAndDescReturnsFalse() {
            // title and description are both null by default
            assertFalse(entry.matchesQuery("anything"));
        }
    }

    // --- markUpdated() / markLoaded() ---

    @Nested
    @DisplayName("markUpdated() / markLoaded()")
    class Timestamps {

        @Test
        @DisplayName("markUpdated sets valid ISO instant")
        void markUpdatedSetsIsoInstant() {
            Instant before = Instant.now();
            entry.markUpdated();
            Instant after = Instant.now();

            Instant updated = Instant.parse(entry.getUpdatedAt());
            assertFalse(updated.isBefore(before));
            assertFalse(updated.isAfter(after));
        }

        @Test
        @DisplayName("markLoaded sets valid ISO instant")
        void markLoadedSetsIsoInstant() {
            assertNull(entry.getLoadedAt());

            Instant before = Instant.now();
            entry.markLoaded();
            Instant after = Instant.now();

            Instant loaded = Instant.parse(entry.getLoadedAt());
            assertFalse(loaded.isBefore(before));
            assertFalse(loaded.isAfter(after));
        }
    }

    // --- setVisibleTo(null) safety ---

    @Test
    @DisplayName("setVisibleTo(null) creates empty list, not null")
    void setVisibleToNullCreatesEmptyList() {
        entry.setVisibleTo(null);
        assertNotNull(entry.getVisibleTo());
        assertTrue(entry.getVisibleTo().isEmpty());
    }
}
