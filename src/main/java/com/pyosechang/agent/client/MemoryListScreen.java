package com.pyosechang.agent.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Memory list screen — shows all memories with category tabs, search, and distance sorting.
 */
public class MemoryListScreen extends Screen {
    private static final String[] CATEGORIES = {"all", "storage", "facility", "area", "event", "preference", "skill"};
    private static final int ENTRY_HEIGHT = 24;
    private static final int LIST_TOP = 60;

    private String selectedCategory = "all";
    private EditBox searchBox;
    private List<JsonObject> displayedEntries = new ArrayList<>();
    private int scrollOffset = 0;
    private int selectedIndex = -1;

    public MemoryListScreen() {
        super(Component.literal("Agent Memory"));
    }

    @Override
    protected void init() {
        int centerX = width / 2;

        // Search box
        searchBox = new EditBox(font, centerX - 100, 32, 200, 16, Component.literal("Search..."));
        searchBox.setHint(Component.literal("Search..."));
        searchBox.setResponder(text -> refreshList());
        addRenderableWidget(searchBox);

        // Category tabs
        int tabWidth = 50;
        int totalTabWidth = CATEGORIES.length * (tabWidth + 2);
        int tabStartX = centerX - totalTabWidth / 2;
        for (int i = 0; i < CATEGORIES.length; i++) {
            String cat = CATEGORIES[i];
            String label = cat.substring(0, 1).toUpperCase() + cat.substring(1);
            if (label.length() > 6) label = label.substring(0, 6);
            int x = tabStartX + i * (tabWidth + 2);
            addRenderableWidget(Button.builder(Component.literal(label), btn -> {
                selectedCategory = cat;
                scrollOffset = 0;
                refreshList();
            }).bounds(x, 12, tabWidth, 16).build());
        }

        // New Memory button
        addRenderableWidget(Button.builder(Component.literal("New Memory"), btn -> {
            minecraft.setScreen(new MemoryEditScreen(null, this));
        }).bounds(centerX - 50, height - 28, 100, 20).build());

        refreshList();
    }

    private void refreshList() {
        String query = searchBox != null ? searchBox.getValue() : "";
        String category = "all".equals(selectedCategory) ? null : selectedCategory;

        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        if (category != null) body.addProperty("category", category);

        BridgeClient.post("/memory/search", body).thenAccept(result -> {
            if (result.has("entries")) {
                List<JsonObject> entries = new ArrayList<>();
                for (JsonElement el : result.getAsJsonArray("entries")) {
                    entries.add(el.getAsJsonObject());
                }
                // Must update on render thread
                Minecraft.getInstance().tell(() -> {
                    displayedEntries = entries;
                    selectedIndex = -1;
                });
            }
        });
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(font, title, width / 2, 2, 0xFFFFFF);

        // Active category indicator
        guiGraphics.drawString(font, "Category: " + selectedCategory, 4, height - 12, 0xAAAAAA);

        // List entries
        int listBottom = height - 36;
        int visibleEntries = (listBottom - LIST_TOP) / ENTRY_HEIGHT;
        int maxScroll = Math.max(0, displayedEntries.size() - visibleEntries);
        scrollOffset = Math.min(scrollOffset, maxScroll);

        for (int i = 0; i < visibleEntries && i + scrollOffset < displayedEntries.size(); i++) {
            int idx = i + scrollOffset;
            JsonObject entry = displayedEntries.get(idx);
            int y = LIST_TOP + i * ENTRY_HEIGHT;

            // Highlight selected
            if (idx == selectedIndex) {
                guiGraphics.fill(4, y, width - 4, y + ENTRY_HEIGHT - 2, 0x40FFFFFF);
            }
            // Hover highlight
            if (mouseX >= 4 && mouseX < width - 4 && mouseY >= y && mouseY < y + ENTRY_HEIGHT - 2) {
                guiGraphics.fill(4, y, width - 4, y + ENTRY_HEIGHT - 2, 0x20FFFFFF);
            }

            String cat = entry.has("category") ? entry.get("category").getAsString() : "?";
            String titleText = entry.has("title") ? entry.get("title").getAsString() : "Untitled";
            String desc = entry.has("description") ? entry.get("description").getAsString() : "";
            String id = entry.has("id") ? entry.get("id").getAsString() : "";

            // Category color
            int catColor = getCategoryColor(cat);
            guiGraphics.drawString(font, "[" + cat + "]", 8, y + 2, catColor);

            int titleX = 8 + font.width("[" + cat + "] ");
            guiGraphics.drawString(font, titleText, titleX, y + 2, 0xFFFFFF);

            // ID on the right
            guiGraphics.drawString(font, id, width - font.width(id) - 8, y + 2, 0x808080);

            // Description on second line (truncated)
            if (!desc.isEmpty()) {
                String truncated = desc.length() > 80 ? desc.substring(0, 77) + "..." : desc;
                guiGraphics.drawString(font, truncated, 16, y + 13, 0xAAAAAA);
            }
        }

        // Entry count
        guiGraphics.drawString(font, displayedEntries.size() + " entries", width - font.width(displayedEntries.size() + " entries") - 4, height - 12, 0x808080);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int listBottom = height - 36;
            int visibleEntries = (listBottom - LIST_TOP) / ENTRY_HEIGHT;
            for (int i = 0; i < visibleEntries && i + scrollOffset < displayedEntries.size(); i++) {
                int y = LIST_TOP + i * ENTRY_HEIGHT;
                if (mouseX >= 4 && mouseX < width - 4 && mouseY >= y && mouseY < y + ENTRY_HEIGHT - 2) {
                    int idx = i + scrollOffset;
                    selectedIndex = idx;
                    // Open edit screen
                    JsonObject entry = displayedEntries.get(idx);
                    minecraft.setScreen(new MemoryEditScreen(entry, this));
                    return true;
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
            case "preference" -> 0xFF55FF;
            case "skill" -> 0x5555FF;
            default -> 0xAAAAAA;
        };
    }
}
