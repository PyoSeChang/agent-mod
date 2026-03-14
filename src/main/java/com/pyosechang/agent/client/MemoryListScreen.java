package com.pyosechang.agent.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pyosechang.agent.core.AgentManager;
import com.pyosechang.agent.core.memory.MemoryEntry;
import com.pyosechang.agent.core.memory.MemoryManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Memory list screen — dropdown for category + agent scope, search, scope badges.
 * Reads directly from MemoryManager (no HTTP bridge).
 */
public class MemoryListScreen extends Screen {
    private static final String[] CATEGORIES = {"all", "storage", "facility", "area", "event", "skill"};
    private static final int[] CATEGORY_COLORS = {0xAAAAAA, 0x55FF55, 0xFFAA00, 0x55FFFF, 0xFFFF55, 0x5555FF};
    private static final int ENTRY_HEIGHT = 24;
    private static final int LIST_TOP = 38;

    private String selectedCategory = "all";
    private String selectedScope = "all";
    private EditBox searchBox;
    private List<JsonObject> displayedEntries = new ArrayList<>();
    private int scrollOffset = 0;
    private int selectedIndex = -1;

    private DropdownWidget categoryDropdown;
    private DropdownWidget scopeDropdown;

    public MemoryListScreen() {
        super(Component.literal("Agent Memory"));
    }

    String getSelectedScope() { return selectedScope; }

    @Override
    protected void init() {
        int centerX = width / 2;

        categoryDropdown = new DropdownWidget(font, 8, 18, 80, 16, Component.literal("Category"));
        for (int i = 0; i < CATEGORIES.length; i++) {
            String cat = CATEGORIES[i];
            String label = cat.substring(0, 1).toUpperCase() + cat.substring(1);
            categoryDropdown.addEntry(cat, label, CATEGORY_COLORS[i]);
        }
        categoryDropdown.setSelectedByKey(selectedCategory);
        categoryDropdown.setOnSelect(entry -> {
            selectedCategory = entry.key();
            scrollOffset = 0;
            refreshList();
        });
        addRenderableWidget(categoryDropdown);

        scopeDropdown = new DropdownWidget(font, 92, 18, 80, 16, Component.literal("Scope"));
        scopeDropdown.addEntry("all", "All", 0xFFFFFF);
        scopeDropdown.setSelectedByKey(selectedScope);
        scopeDropdown.setOnSelect(entry -> {
            selectedScope = entry.key();
            scrollOffset = 0;
            refreshList();
        });
        addRenderableWidget(scopeDropdown);

        searchBox = new EditBox(font, 176, 18, width - 184, 16, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search memories..."));
        searchBox.setResponder(text -> refreshList());
        addRenderableWidget(searchBox);

        addRenderableWidget(Button.builder(Component.literal("+ New Memory"), btn -> {
            minecraft.setScreen(new MemoryEditScreen(null, this));
        }).bounds(centerX - 55, height - 24, 110, 20).build());

        loadAgentNames();
        refreshList();
    }

    private void loadAgentNames() {
        // Direct access — AgentManager has agent directories on disk
        try {
            java.nio.file.Path agentsDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve(".agent/agents");
            if (java.nio.file.Files.isDirectory(agentsDir)) {
                String currentKey = scopeDropdown.getSelectedKey();
                scopeDropdown.clearEntries();
                scopeDropdown.addEntry("all", "All", 0xFFFFFF);
                try (var dirs = java.nio.file.Files.list(agentsDir)) {
                    dirs.filter(java.nio.file.Files::isDirectory).forEach(dir -> {
                        String name = dir.getFileName().toString();
                        scopeDropdown.addEntry(name, name, 0x55BBFF);
                    });
                }
                scopeDropdown.setSelectedByKey(currentKey);
            }
        } catch (Exception ignored) {}
    }

    private void refreshList() {
        String query = searchBox != null ? searchBox.getValue() : "";
        String category = "all".equals(selectedCategory) ? null : selectedCategory;

        String scope;
        if ("all".equals(selectedScope)) {
            scope = "all";
        } else if ("global".equals(selectedScope)) {
            scope = "global";
        } else {
            scope = "agent:" + selectedScope;
        }

        List<MemoryEntry> results = MemoryManager.getInstance().search(query, category, scope);
        MemoryManager mm = MemoryManager.getInstance();

        List<JsonObject> entries = new ArrayList<>();
        for (MemoryEntry e : results) {
            entries.add(mm.entryToJson(e));
        }
        displayedEntries = entries;
        selectedIndex = -1;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        g.fill(0, 0, width, 14, 0x80000000);
        g.drawCenteredString(font, title, width / 2, 3, 0xFFFFFF);

        g.fill(4, LIST_TOP - 2, width - 4, LIST_TOP - 1, 0x30FFFFFF);

        int listBottom = height - 30;
        int visibleEntries = (listBottom - LIST_TOP) / ENTRY_HEIGHT;
        int maxScroll = Math.max(0, displayedEntries.size() - visibleEntries);
        scrollOffset = Math.min(scrollOffset, maxScroll);

        if (displayedEntries.isEmpty()) {
            g.drawCenteredString(font, "No memories found", width / 2, LIST_TOP + 20, 0x808080);
        }

        for (int i = 0; i < visibleEntries && i + scrollOffset < displayedEntries.size(); i++) {
            int idx = i + scrollOffset;
            JsonObject entry = displayedEntries.get(idx);
            int y = LIST_TOP + i * ENTRY_HEIGHT;

            boolean hovered = mouseX >= 4 && mouseX < width - 4 && mouseY >= y && mouseY < y + ENTRY_HEIGHT - 2;
            if (idx == selectedIndex) {
                g.fill(4, y, width - 4, y + ENTRY_HEIGHT - 2, 0x40FFFFFF);
            } else if (hovered) {
                g.fill(4, y, width - 4, y + ENTRY_HEIGHT - 2, 0x20FFFFFF);
            }

            String cat = entry.has("category") ? entry.get("category").getAsString() : "?";
            String titleText = entry.has("title") ? entry.get("title").getAsString() : "Untitled";
            String desc = entry.has("description") ? entry.get("description").getAsString() : "";

            int catColor = getCategoryColor(cat);
            String catLabel = "[" + cat + "]";
            g.drawString(font, catLabel, 8, y + 2, catColor);

            int titleX = 8 + font.width(catLabel) + 4;
            g.drawString(font, titleText, titleX, y + 2, 0xFFFFFF);

            String scopeBadge = getScopeBadge(entry);
            int badgeColor = "global".equals(scopeBadge) ? 0x55FF55 : 0x55BBFF;
            int badgeX = width - font.width(scopeBadge) - 8;
            g.drawString(font, scopeBadge, badgeX, y + 2, badgeColor);

            if (!desc.isEmpty()) {
                int maxW = width - 24;
                String truncated = desc;
                if (font.width(truncated) > maxW) {
                    while (font.width(truncated + "...") > maxW && truncated.length() > 0) {
                        truncated = truncated.substring(0, truncated.length() - 1);
                    }
                    truncated = truncated + "...";
                }
                g.drawString(font, truncated, 16, y + 13, 0x909090);
            }
        }

        if (displayedEntries.size() > visibleEntries && visibleEntries > 0) {
            int barH = Math.max(8, visibleEntries * (listBottom - LIST_TOP) / displayedEntries.size());
            int barY = LIST_TOP + (scrollOffset * (listBottom - LIST_TOP - barH)) / Math.max(1, maxScroll);
            g.fill(width - 4, barY, width - 2, barY + barH, 0x60FFFFFF);
        }

        g.drawString(font, displayedEntries.size() + " entries", 4, height - 10, 0x808080);

        super.render(g, mouseX, mouseY, partialTick);

        categoryDropdown.renderDropdown(g, mouseX, mouseY);
        scopeDropdown.renderDropdown(g, mouseX, mouseY);
    }

    private String getScopeBadge(JsonObject entry) {
        if (entry.has("visible_to") && entry.get("visible_to").isJsonArray()) {
            JsonArray vt = entry.getAsJsonArray("visible_to");
            if (vt.isEmpty()) return "global";
            StringBuilder sb = new StringBuilder();
            for (var el : vt) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(el.getAsString());
            }
            return sb.toString();
        }
        return "global";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (categoryDropdown.isOpen()) {
                boolean handled = categoryDropdown.mouseClicked(mouseX, mouseY, button);
                if (handled) return true;
            }
            if (scopeDropdown.isOpen()) {
                boolean handled = scopeDropdown.mouseClicked(mouseX, mouseY, button);
                if (handled) return true;
            }

            if (!categoryDropdown.isOpen() && !scopeDropdown.isOpen()) {
                int listBottom = height - 30;
                int visibleEntries = (listBottom - LIST_TOP) / ENTRY_HEIGHT;
                for (int i = 0; i < visibleEntries && i + scrollOffset < displayedEntries.size(); i++) {
                    int y = LIST_TOP + i * ENTRY_HEIGHT;
                    if (mouseX >= 4 && mouseX < width - 4 && mouseY >= y && mouseY < y + ENTRY_HEIGHT - 2) {
                        int idx = i + scrollOffset;
                        selectedIndex = idx;
                        minecraft.setScreen(new MemoryEditScreen(displayedEntries.get(idx), this));
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollOffset -= (int) delta;
        scrollOffset = Math.max(0, scrollOffset);
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private int getCategoryColor(String cat) {
        return switch (cat) {
            case "storage" -> 0x55FF55;
            case "facility" -> 0xFFAA00;
            case "area" -> 0x55FFFF;
            case "event" -> 0xFFFF55;
            case "skill" -> 0x5555FF;
            default -> 0xAAAAAA;
        };
    }
}
