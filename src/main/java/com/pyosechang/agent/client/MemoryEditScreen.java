package com.pyosechang.agent.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.*;

/**
 * Memory edit screen — two-column layout.
 * Left: Title, Desc, Content.
 * Right: Category, Tags, Visibility, Location.
 * Agent selection as overlay. Buttons fixed at bottom.
 */
public class MemoryEditScreen extends Screen {
    private static final String[] CATEGORIES = {"storage", "facility", "area", "event", "preference", "skill"};
    private static final int[] CATEGORY_COLORS = {0x55FF55, 0xFFAA00, 0x55FFFF, 0xFFFF55, 0xFF55FF, 0x5555FF};

    private final JsonObject existingEntry;
    private final MemoryListScreen parent;

    // Left column
    private EditBox titleBox;
    private MultiLineEditBox descBox;
    private MultiLineEditBox contentBox;

    // Right column
    private EditBox tagsBox;
    private DropdownWidget categoryDropdown;
    private Button visibilityBtn;
    private Button[] locationButtons;
    private EditBox xBox, yBox, zBox, radiusBox;
    private EditBox x1Box, z1Box, x2Box, z2Box;
    private Button currentPosBtn, markAreaBtn;

    private String selectedCategory = "event";
    private String locationType = "point";

    // m:n visibility — agent overlay
    private boolean globalScope = true;
    private boolean agentOverlayOpen = false;
    private Set<String> selectedAgents = new HashSet<>();
    private List<String> availableAgents = new ArrayList<>();

    private BlockPos pendingC1, pendingC2;

    // Layout
    private int leftX, leftW, rightX, rightW;
    private int agentBtnY; // y of the visibility button (for overlay positioning)

    public MemoryEditScreen(JsonObject existingEntry, MemoryListScreen parent) {
        super(Component.literal(existingEntry != null ? "Edit Memory" : "New Memory"));
        this.existingEntry = existingEntry;
        this.parent = parent;
    }

    public MemoryEditScreen(JsonObject existingEntry, MemoryListScreen parent,
                            String locType, BlockPos c1, BlockPos c2) {
        this(existingEntry, parent);
        this.locationType = locType;
        this.pendingC1 = c1;
        this.pendingC2 = c2;
    }

    @Override
    protected void init() {
        int gap = 14;
        int totalW = Math.min(width - 30, 520);
        leftW = (int)(totalW * 0.55);
        rightW = totalW - leftW - gap;
        int startX = (width - totalW) / 2;
        leftX = startX;
        rightX = startX + leftW + gap;

        int btnY = height - 28;

        // === Left column: Title, Desc, Content ===
        int y = 24;

        titleBox = new EditBox(font, leftX, y + 12, leftW, 16, Component.empty());
        titleBox.setMaxLength(100);
        addRenderableWidget(titleBox);
        y += 36;

        int descH = 80;
        descBox = new MultiLineEditBox(font, leftX, y + 12, leftW, descH,
            Component.literal(""), Component.literal("Desc"));
        descBox.setCharacterLimit(300);
        addRenderableWidget(descBox);
        y += descH + 20;

        // Content: leave 36px gap above buttons for char count
        int contentH = Math.max(60, btnY - y - 36);
        contentBox = new MultiLineEditBox(font, leftX, y + 12, leftW, contentH,
            Component.literal(""), Component.literal("Content"));
        contentBox.setCharacterLimit(500);
        addRenderableWidget(contentBox);

        // === Right column: Category, Tags, Visibility, Location ===
        y = 24;

        // Category dropdown (first)
        categoryDropdown = new DropdownWidget(font, rightX, y + 12, rightW, 16, Component.literal("Category"));
        for (int i = 0; i < CATEGORIES.length; i++) {
            String cat = CATEGORIES[i];
            String label = cat.substring(0, 1).toUpperCase() + cat.substring(1);
            categoryDropdown.addEntry(cat, label, CATEGORY_COLORS[i]);
        }
        categoryDropdown.setSelectedByKey(selectedCategory);
        categoryDropdown.setOnSelect(entry -> selectedCategory = entry.key());
        addRenderableWidget(categoryDropdown);
        y += 36;

        // Tags (second)
        tagsBox = new EditBox(font, rightX, y + 12, rightW, 16, Component.empty());
        tagsBox.setMaxLength(200);
        tagsBox.setHint(Component.literal("comma separated"));
        addRenderableWidget(tagsBox);
        y += 36;

        // Visibility toggle — clicking opens agent overlay
        agentBtnY = y + 12;
        visibilityBtn = addRenderableWidget(Button.builder(
            Component.literal(globalScope ? "Global (all agents)" : "Specific agents"),
            btn -> {
                if (globalScope) {
                    globalScope = false;
                    agentOverlayOpen = true;
                } else {
                    agentOverlayOpen = !agentOverlayOpen;
                }
                btn.setMessage(Component.literal(globalScope ? "Global (all agents)" : "Specific agents"));
            }
        ).bounds(rightX, agentBtnY, rightW, 16).build());
        y += 50; // extra margin for agent names display below button

        // Location section
        int locBtnW = (rightW - 4) / 3;
        locationButtons = new Button[3];
        String[] locTypes = {"point", "area", "none"};
        for (int i = 0; i < 3; i++) {
            String lt = locTypes[i];
            String label = lt.substring(0, 1).toUpperCase() + lt.substring(1);
            locationButtons[i] = addRenderableWidget(Button.builder(Component.literal(label), btn -> {
                locationType = lt;
                updateLocationButtons();
                updateLocationVisibility();
            }).bounds(rightX + i * (locBtnW + 2), y + 12, locBtnW, 16).build());
        }
        y += 48; // extra margin between location buttons and coordinate labels/fields

        // Coordinate fields
        int smallW = (rightW - 12) / 4;
        // Point: X Y Z R
        xBox = createCoordField(rightX, y, smallW);
        yBox = createCoordField(rightX + smallW + 4, y, smallW);
        zBox = createCoordField(rightX + (smallW + 4) * 2, y, smallW);
        radiusBox = createCoordField(rightX + (smallW + 4) * 3, y, smallW);
        radiusBox.setValue("5");

        // Area: X1 Z1 X2 Z2 (same row position, toggled)
        x1Box = createCoordField(rightX, y, smallW);
        z1Box = createCoordField(rightX + smallW + 4, y, smallW);
        x2Box = createCoordField(rightX + (smallW + 4) * 2, y, smallW);
        z2Box = createCoordField(rightX + (smallW + 4) * 3, y, smallW);
        y += 20;

        // Utility buttons
        int utilBtnW = (rightW - 4) / 2;
        currentPosBtn = addRenderableWidget(Button.builder(Component.literal("Current Pos"), btn -> {
            if (minecraft.player != null) {
                BlockPos pos = minecraft.player.blockPosition();
                xBox.setValue(String.valueOf(pos.getX()));
                yBox.setValue(String.valueOf(pos.getY()));
                zBox.setValue(String.valueOf(pos.getZ()));
            }
        }).bounds(rightX, y, utilBtnW, 16).build());

        markAreaBtn = addRenderableWidget(Button.builder(Component.literal("Mark Area"), btn -> {
            AreaMarkHandler.start((c1, c2) -> {
                minecraft.setScreen(new MemoryEditScreen(existingEntry, parent, "area", c1, c2));
            });
        }).bounds(rightX + utilBtnW + 4, y, utilBtnW, 16).build());

        // === Bottom buttons (fixed) ===
        int btnW = 68;
        int btnCount = existingEntry != null ? 3 : 2;
        int totalBtnW = btnCount * btnW + (btnCount - 1) * 6;
        int ax = width / 2 - totalBtnW / 2;

        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> save())
            .bounds(ax, btnY, btnW, 20).build());
        ax += btnW + 6;
        if (existingEntry != null) {
            addRenderableWidget(Button.builder(Component.literal("Delete"), btn -> delete())
                .bounds(ax, btnY, btnW, 20).build());
            ax += btnW + 6;
        }
        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> {
            minecraft.setScreen(parent);
        }).bounds(ax, btnY, btnW, 20).build());

        // Populate
        if (existingEntry != null) populateFromEntry();

        if (parent != null && existingEntry == null) {
            String ps = parent.getSelectedScope();
            if (ps != null && !"all".equals(ps)) {
                globalScope = false;
                selectedAgents.add(ps);
            }
        }

        if (pendingC1 != null && pendingC2 != null) {
            locationType = "area";
            x1Box.setValue(String.valueOf(Math.min(pendingC1.getX(), pendingC2.getX())));
            z1Box.setValue(String.valueOf(Math.min(pendingC1.getZ(), pendingC2.getZ())));
            x2Box.setValue(String.valueOf(Math.max(pendingC1.getX(), pendingC2.getX())));
            z2Box.setValue(String.valueOf(Math.max(pendingC1.getZ(), pendingC2.getZ())));
            yBox.setValue(String.valueOf(pendingC1.getY()));
        }

        loadAgentNames();
        updateLocationButtons();
        updateLocationVisibility();
    }

    private EditBox createCoordField(int x, int y, int w) {
        EditBox box = new EditBox(font, x, y, w, 14, Component.empty());
        box.setMaxLength(10);
        addRenderableWidget(box);
        return box;
    }

    private void populateFromEntry() {
        if (existingEntry.has("title")) titleBox.setValue(existingEntry.get("title").getAsString());
        if (existingEntry.has("description")) descBox.setValue(existingEntry.get("description").getAsString());
        if (existingEntry.has("content")) contentBox.setValue(existingEntry.get("content").getAsString());
        if (existingEntry.has("category")) {
            selectedCategory = existingEntry.get("category").getAsString();
            categoryDropdown.setSelectedByKey(selectedCategory);
        }
        if (existingEntry.has("tags") && existingEntry.get("tags").isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (var el : existingEntry.getAsJsonArray("tags")) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(el.getAsString());
            }
            tagsBox.setValue(sb.toString());
        }
        if (existingEntry.has("visible_to") && existingEntry.get("visible_to").isJsonArray()) {
            JsonArray vt = existingEntry.getAsJsonArray("visible_to");
            if (vt.isEmpty()) {
                globalScope = true;
            } else {
                globalScope = false;
                for (JsonElement el : vt) selectedAgents.add(el.getAsString());
            }
        } else if (existingEntry.has("scope")) {
            String scope = existingEntry.get("scope").getAsString();
            if (scope.startsWith("agent:")) {
                globalScope = false;
                selectedAgents.add(scope.substring("agent:".length()));
            }
        }
        if (existingEntry.has("location") && existingEntry.get("location").isJsonObject()) {
            JsonObject loc = existingEntry.getAsJsonObject("location");
            locationType = loc.has("type") ? loc.get("type").getAsString() : "point";
            if ("area".equals(locationType)) {
                if (loc.has("x1")) x1Box.setValue(String.valueOf(loc.get("x1").getAsInt()));
                if (loc.has("z1")) z1Box.setValue(String.valueOf(loc.get("z1").getAsInt()));
                if (loc.has("x2")) x2Box.setValue(String.valueOf(loc.get("x2").getAsInt()));
                if (loc.has("z2")) z2Box.setValue(String.valueOf(loc.get("z2").getAsInt()));
                if (loc.has("y")) yBox.setValue(String.valueOf(loc.get("y").getAsInt()));
            } else {
                if (loc.has("x")) xBox.setValue(String.valueOf(loc.get("x").getAsInt()));
                if (loc.has("y")) yBox.setValue(String.valueOf(loc.get("y").getAsInt()));
                if (loc.has("z")) zBox.setValue(String.valueOf(loc.get("z").getAsInt()));
                if (loc.has("radius")) radiusBox.setValue(String.valueOf(loc.get("radius").getAsInt()));
            }
        }
        // Update visibility button text
        if (visibilityBtn != null) {
            visibilityBtn.setMessage(Component.literal(globalScope ? "Global (all agents)" : "Specific agents"));
        }
    }

    private void loadAgentNames() {
        BridgeClient.get("/agents/list").thenAccept(result -> {
            if (result.has("agents")) {
                List<String> names = new ArrayList<>();
                for (JsonElement el : result.getAsJsonArray("agents")) {
                    names.add(el.getAsJsonObject().get("name").getAsString());
                }
                Minecraft.getInstance().tell(() -> availableAgents = names);
            }
        });
    }

    private void updateLocationButtons() {
        String[] locTypes = {"point", "area", "none"};
        for (int i = 0; i < 3; i++) {
            locationButtons[i].active = !locTypes[i].equals(locationType);
        }
    }

    private void updateLocationVisibility() {
        boolean isPoint = "point".equals(locationType);
        boolean isArea = "area".equals(locationType);
        boolean isNone = "none".equals(locationType);
        xBox.visible = isPoint; yBox.visible = isPoint;
        zBox.visible = isPoint; radiusBox.visible = isPoint;
        x1Box.visible = isArea; z1Box.visible = isArea;
        x2Box.visible = isArea; z2Box.visible = isArea;
        currentPosBtn.visible = !isNone;
        markAreaBtn.visible = !isNone;
    }

    private void save() {
        JsonObject body = new JsonObject();
        body.addProperty("title", titleBox.getValue());
        body.addProperty("description", descBox.getValue());
        body.addProperty("content", contentBox.getValue());
        body.addProperty("category", selectedCategory);

        JsonArray tags = new JsonArray();
        for (String tag : tagsBox.getValue().split(",")) {
            String t = tag.trim();
            if (!t.isEmpty()) tags.add(t);
        }
        body.add("tags", tags);

        if (!"none".equals(locationType)) {
            JsonObject loc = new JsonObject();
            loc.addProperty("type", locationType);
            if ("area".equals(locationType)) {
                loc.addProperty("x1", parseDouble(x1Box.getValue()));
                loc.addProperty("z1", parseDouble(z1Box.getValue()));
                loc.addProperty("x2", parseDouble(x2Box.getValue()));
                loc.addProperty("z2", parseDouble(z2Box.getValue()));
                loc.addProperty("y", parseDouble(yBox.getValue()));
            } else {
                loc.addProperty("x", parseDouble(xBox.getValue()));
                loc.addProperty("y", parseDouble(yBox.getValue()));
                loc.addProperty("z", parseDouble(zBox.getValue()));
                loc.addProperty("radius", parseDouble(radiusBox.getValue()));
            }
            body.add("location", loc);
        }

        JsonArray visibleTo = new JsonArray();
        if (!globalScope) {
            for (String agent : selectedAgents) visibleTo.add(agent);
        }
        body.add("visible_to", visibleTo);

        String path;
        if (existingEntry != null) {
            body.addProperty("id", existingEntry.get("id").getAsString());
            path = "/memory/update";
        } else {
            path = "/memory/create";
        }

        BridgeClient.post(path, body).thenAccept(result -> {
            Minecraft.getInstance().tell(() -> minecraft.setScreen(parent));
        });
    }

    private void delete() {
        if (existingEntry == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("id", existingEntry.get("id").getAsString());
        BridgeClient.post("/memory/delete", body).thenAccept(result -> {
            Minecraft.getInstance().tell(() -> minecraft.setScreen(parent));
        });
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return 0; }
    }

    // === Rendering ===

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        // Title bar
        g.fill(0, 0, width, 14, 0x80000000);
        g.drawCenteredString(font, title, width / 2, 3, 0xFFFFFF);

        // === Left column labels ===
        int y = 24;
        g.drawString(font, "Title", leftX, y, 0x999999);
        y += 36;
        g.drawString(font, "Description", leftX, y, 0x999999);
        y += 80 + 20;
        g.drawString(font, "Content", leftX, y, 0x999999);

        // === Right column labels ===
        y = 24;
        g.drawString(font, "Category", rightX, y, 0x999999);
        y += 36;
        g.drawString(font, "Tags", rightX, y, 0x999999);
        y += 36;
        g.drawString(font, "Visibility", rightX, y, 0x999999);

        // Show selected agent names below button (only when specific agents)
        if (!globalScope && !selectedAgents.isEmpty()) {
            String names = String.join(", ", selectedAgents);
            int maxW = rightW;
            if (font.width(names) > maxW) {
                while (font.width(names + "..") > maxW && names.length() > 1)
                    names = names.substring(0, names.length() - 1);
                names += "..";
            }
            g.drawString(font, names, rightX, y + 30, 0x55BBFF);
        }
        y += 50;

        // Location label
        g.drawString(font, "Location", rightX, y, 0x999999);
        y += 48;

        // Coordinate labels above fields
        int smallW = (rightW - 12) / 4;
        if ("point".equals(locationType)) {
            g.drawString(font, "X", rightX + 2, y - 12, 0x777777);
            g.drawString(font, "Y", rightX + smallW + 6, y - 12, 0x777777);
            g.drawString(font, "Z", rightX + (smallW + 4) * 2 + 2, y - 12, 0x777777);
            g.drawString(font, "R", rightX + (smallW + 4) * 3 + 2, y - 12, 0x777777);
        } else if ("area".equals(locationType)) {
            g.drawString(font, "X1", rightX + 2, y - 12, 0x777777);
            g.drawString(font, "Z1", rightX + smallW + 6, y - 12, 0x777777);
            g.drawString(font, "X2", rightX + (smallW + 4) * 2 + 2, y - 12, 0x777777);
            g.drawString(font, "Z2", rightX + (smallW + 4) * 3 + 2, y - 12, 0x777777);
        }

        // Render widgets
        super.render(g, mouseX, mouseY, partialTick);

        // === Overlays (on top of everything) ===
        categoryDropdown.renderDropdown(g, mouseX, mouseY);

        // Agent selection overlay
        if (agentOverlayOpen && !globalScope && !availableAgents.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);

            int oX = rightX;
            int oY = agentBtnY + 16;
            int oH = availableAgents.size() * 14 + 4;

            // Background + border
            g.fill(oX - 1, oY - 1, oX + rightW + 1, oY + oH + 1, 0xFF808080);
            g.fill(oX, oY, oX + rightW, oY + oH, 0xFF1A1A1A);

            for (int i = 0; i < availableAgents.size(); i++) {
                String agent = availableAgents.get(i);
                boolean checked = selectedAgents.contains(agent);
                int ay = oY + 2 + i * 14;

                // Hover
                boolean hovered = mouseX >= oX && mouseX < oX + rightW && mouseY >= ay && mouseY < ay + 13;
                if (hovered) g.fill(oX, ay - 1, oX + rightW, ay + 12, 0xFF333333);

                // Checkbox
                int cbX = oX + 4;
                g.fill(cbX, ay, cbX + 10, ay + 10, 0xFF222222);
                g.fill(cbX, ay, cbX + 10, ay + 1, 0xFF555555);
                g.fill(cbX, ay + 9, cbX + 10, ay + 10, 0xFF555555);
                g.fill(cbX, ay, cbX + 1, ay + 10, 0xFF555555);
                g.fill(cbX + 9, ay, cbX + 10, ay + 10, 0xFF555555);
                if (checked) g.fill(cbX + 2, ay + 2, cbX + 8, ay + 8, 0xFF55FF55);

                g.drawString(font, agent, cbX + 14, ay + 1, checked ? 0xFFFFFF : 0x999999);

                // Separator
                if (i < availableAgents.size() - 1) {
                    g.fill(oX + 2, ay + 12, oX + rightW - 2, ay + 13, 0xFF2A2A2A);
                }
            }

            g.pose().popPose();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Category dropdown priority
            if (categoryDropdown.isOpen()) {
                boolean handled = categoryDropdown.mouseClicked(mouseX, mouseY, button);
                if (handled) return true;
            }

            // Agent overlay clicks
            if (agentOverlayOpen && !globalScope && !availableAgents.isEmpty()) {
                int oX = rightX;
                int oY = agentBtnY + 16;

                for (int i = 0; i < availableAgents.size(); i++) {
                    String agent = availableAgents.get(i);
                    int ay = oY + 2 + i * 14;
                    if (mouseX >= oX && mouseX < oX + rightW && mouseY >= ay && mouseY < ay + 13) {
                        if (selectedAgents.contains(agent)) selectedAgents.remove(agent);
                        else selectedAgents.add(agent);
                        return true;
                    }
                }

                // Click on visibility button toggles back to global
                if (mouseX >= rightX && mouseX < rightX + rightW
                    && mouseY >= agentBtnY && mouseY < agentBtnY + 16) {
                    // Let the button handle it normally
                } else {
                    // Click outside overlay closes it
                    agentOverlayOpen = false;
                    if (selectedAgents.isEmpty()) {
                        globalScope = true;
                        visibilityBtn.setMessage(Component.literal("Global (all agents)"));
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
