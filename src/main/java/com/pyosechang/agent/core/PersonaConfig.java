package com.pyosechang.agent.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parsed representation of an agent's PERSONA.md file.
 * Sections: ## Role, ## Personality, ## Tools, ## Acquaintances
 */
public class PersonaConfig {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * An acquaintance entry: name + description.
     * Like skill memory — loaded into observation when relevant.
     */
    public record Acquaintance(String name, String description) {}

    private final String name;
    private final String role;
    private final String personality;
    private final List<String> tools;
    private final List<Acquaintance> acquaintances;
    private final String rawContent;

    public PersonaConfig(String name, String role, String personality,
                         List<String> tools, List<Acquaintance> acquaintances, String rawContent) {
        this.name = name;
        this.role = role;
        this.personality = personality;
        this.tools = Collections.unmodifiableList(tools);
        this.acquaintances = Collections.unmodifiableList(acquaintances);
        this.rawContent = rawContent;
    }

    public String getName() { return name; }
    public String getRole() { return role; }
    public String getPersonality() { return personality; }
    public List<String> getTools() { return tools; }
    public List<Acquaintance> getAcquaintances() { return acquaintances; }
    public String getRawContent() { return rawContent; }

    /** @return true if tools list is empty (meaning all tools allowed) */
    public boolean isAllToolsAllowed() { return tools.isEmpty(); }

    /**
     * Parse a PERSONA.md file into a PersonaConfig.
     */
    public static PersonaConfig parse(String name, Path personaFile) {
        try {
            String content = Files.readString(personaFile, StandardCharsets.UTF_8);
            return parseContent(name, content);
        } catch (IOException e) {
            LOGGER.warn("Failed to read PERSONA.md for '{}': {}", name, e.getMessage());
            return defaultPersona(name);
        }
    }

    static PersonaConfig parseContent(String name, String content) {
        String role = "";
        String personality = "";
        List<String> tools = new ArrayList<>();

        String currentSection = "";
        StringBuilder sectionBuffer = new StringBuilder();

        for (String line : content.split("\n")) {
            String trimmed = line.trim();

            if (trimmed.startsWith("## ")) {
                // Save previous section
                saveParsedSection(currentSection, sectionBuffer.toString().trim(),
                    s -> role, s -> personality, tools);

                currentSection = trimmed.substring(3).trim().toLowerCase();
                sectionBuffer = new StringBuilder();
                continue;
            }

            sectionBuffer.append(line).append("\n");
        }

        // Save last section
        String roleResult = role;
        String personalityResult = personality;
        // We need to handle the final section
        String finalSection = currentSection;
        String finalContent = sectionBuffer.toString().trim();

        if (finalSection.equals("role")) {
            roleResult = finalContent;
        } else if (finalSection.equals("personality")) {
            personalityResult = finalContent;
        } else if (finalSection.equals("tools")) {
            parseToolsList(finalContent, tools);
        }

        // Also parse non-final sections properly
        // Re-parse to handle all sections correctly
        return parseAllSections(name, content);
    }

    private static PersonaConfig parseAllSections(String name, String content) {
        String role = "";
        String personality = "";
        List<String> tools = new ArrayList<>();
        List<Acquaintance> acquaintances = new ArrayList<>();

        String currentSection = "";
        StringBuilder sectionBuffer = new StringBuilder();

        String[] lines = content.split("\n");
        for (int i = 0; i <= lines.length; i++) {
            String trimmed = i < lines.length ? lines[i].trim() : null;

            boolean isNewSection = trimmed != null && trimmed.startsWith("## ");
            boolean isEnd = i == lines.length;

            if (isNewSection || isEnd) {
                // Process previous section
                String text = sectionBuffer.toString().trim();
                switch (currentSection) {
                    case "role" -> role = text;
                    case "personality" -> personality = text;
                    case "tools" -> parseToolsList(text, tools);
                    case "acquaintances" -> parseAcquaintancesList(text, acquaintances);
                }
                if (isNewSection) {
                    currentSection = trimmed.substring(3).trim().toLowerCase();
                    sectionBuffer = new StringBuilder();
                }
            } else if (i < lines.length) {
                sectionBuffer.append(lines[i]).append("\n");
            }
        }

        return new PersonaConfig(name, role, personality, tools, acquaintances, content);
    }

    private static void saveParsedSection(String section, String text,
                                          java.util.function.Function<String, String> roleSetter,
                                          java.util.function.Function<String, String> personalitySetter,
                                          List<String> tools) {
        // Unused — replaced by parseAllSections
    }

    private static void parseToolsList(String text, List<String> tools) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                String tool = trimmed.substring(2).trim();
                if (!tool.isEmpty()) {
                    tools.add(tool);
                }
            }
        }
    }

    /**
     * Parse acquaintances list: "- name: description" format.
     */
    private static void parseAcquaintancesList(String text, List<Acquaintance> acquaintances) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                String entry = trimmed.substring(2).trim();
                int colonIdx = entry.indexOf(':');
                if (colonIdx > 0) {
                    String acqName = entry.substring(0, colonIdx).trim();
                    String acqDesc = entry.substring(colonIdx + 1).trim();
                    if (!acqName.isEmpty()) {
                        acquaintances.add(new Acquaintance(acqName, acqDesc));
                    }
                } else if (!entry.isEmpty()) {
                    acquaintances.add(new Acquaintance(entry, ""));
                }
            }
        }
    }

    /**
     * Create a default persona when no PERSONA.md exists.
     * Empty tools list = all tools allowed.
     */
    public static PersonaConfig defaultPersona(String name) {
        String defaultContent = String.format("""
            # %s

            ## Role
            General-purpose agent

            ## Personality
            Helpful and efficient.

            ## Tools
            """, name);
        return new PersonaConfig(name, "General-purpose agent", "Helpful and efficient.",
            List.of(), List.of(), defaultContent.trim());
    }

    /**
     * @return comma-separated tool names for env var, or empty string if all allowed
     */
    public String getToolsCsv() {
        if (tools.isEmpty()) return "";
        return String.join(",", tools);
    }
}
