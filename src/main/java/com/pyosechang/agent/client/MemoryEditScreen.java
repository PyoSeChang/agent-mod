package com.pyosechang.agent.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Memory edit screen — create or edit a memory entry.
 */
public class MemoryEditScreen extends Screen {
    private static final String[] CATEGORIES = {"storage", "facility", "area", "event", "preference", "skill"};
    private static final String[] LOCATION_TYPES = {"point", "area", "none"};

    private final JsonObject existingEntry; // null for new
    private final MemoryListScreen parent;

    private EditBox titleBox;
    private EditBox descBox;
    private EditBox contentBox;
    private EditBox tagsBox;
    private EditBox xBox, yBox, zBox, radiusBox;
    private EditBox x1Box, z1Box, x2Box, z2Box;

    private String selectedCategory = "event";
    private String locationType = "point";
    private Button[] categoryButtons;

    public MemoryEditScreen(JsonObject existingEntry, MemoryListScreen parent) {
        super(Component.literal(existingEntry != null ? "Edit Memory" : "New Memory"));
        this.existingEntry = existingEntry;
        this.parent = parent;
    }

    // Constructor for area mark callback
    public MemoryEditScreen(JsonObject existingEntry, MemoryListScreen parent,
                            String locType, BlockPos c1, BlockPos c2) {
        this(existingEntry, parent);
        this.locationType = locType;
        // Will set coordinates in init() after boxes are created
        this.pendingC1 = c1;
        this.pendingC2 = c2;
    }

    private BlockPos pendingC1, pendingC2;

    @Override
    protected void init() {
        int left = width / 2 - 120;
        int fieldWidth = 240;
        int y = 20;

        // Title
        titleBox = addField("Title:", left, y, fieldWidth);
        y += 24;

        // Description
        descBox = addField("Description:", left, y, fieldWidth);
        y += 24;

        // Content
        contentBox = addField("Content:", left, y, fieldWidth);
        y += 24;

        // Tags
        tagsBox = addField("Tags (comma):", left, y, fieldWidth);
        y += 28;

        // Category toggle buttons
        int catY = y;
        categoryButtons = new Button[CATEGORIES.length];
        int catBtnWidth = 38;
        int catStartX = left;
        for (int i = 0; i < CATEGORIES.length; i++) {
            String cat = CATEGORIES[i];
            String label = cat.substring(0, Math.min(cat.length(), 5));
            int bx = catStartX + i * (catBtnWidth + 2);
            categoryButtons[i] = addRenderableWidget(Button.builder(Component.literal(label), btn -> {
                selectedCategory = cat;
                updateCategoryButtons();
            }).bounds(bx, catY, catBtnWidth, 16).build());
        }
        y += 22;

        // Location type toggle
        int locY = y;
        for (int i = 0; i < LOCATION_TYPES.length; i++) {
            String lt = LOCATION_TYPES[i];
            int bx = left + i * 52;
            addRenderableWidget(Button.builder(Component.literal(lt.substring(0, 1).toUpperCase() + lt.substring(1)), btn -> {
                locationType = lt;
                updateLocationVisibility();
            }).bounds(bx, locY, 50, 16).build());
        }
        y += 20;

        // Point location fields
        int smallWidth = 50;
        xBox = addSmallField("X:", left, y, smallWidth);
        yBox = addSmallField("Y:", left + 70, y, smallWidth);
        zBox = addSmallField("Z:", left + 140, y, smallWidth);
        radiusBox = addSmallField("R:", left + 210, y, 30);
        radiusBox.setValue("5");
        y += 20;

        // Area location fields
        x1Box = addSmallField("X1:", left, y, smallWidth);
        z1Box = addSmallField("Z1:", left + 70, y, smallWidth);
        x2Box = addSmallField("X2:", left + 140, y, smallWidth);
        z2Box = addSmallField("Z2:", left + 210, y, 30);
        y += 24;

        // Action buttons
        int btnY = y;
        addRenderableWidget(Button.builder(Component.literal("Current Pos"), btn -> {
            if (minecraft.player != null) {
                BlockPos pos = minecraft.player.blockPosition();
                xBox.setValue(String.valueOf(pos.getX()));
                yBox.setValue(String.valueOf(pos.getY()));
                zBox.setValue(String.valueOf(pos.getZ()));
            }
        }).bounds(left, btnY, 75, 18).build());

        addRenderableWidget(Button.builder(Component.literal("Mark Area"), btn -> {
            AreaMarkHandler.start((c1, c2) -> {
                // Re-open this screen with area coordinates filled in
                minecraft.setScreen(new MemoryEditScreen(existingEntry, parent, "area", c1, c2));
            });
        }).bounds(left + 80, btnY, 75, 18).build());

        y += 26;

        // Save / Delete / Cancel
        int actionY = Math.min(y, height - 28);
        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> save())
            .bounds(left, actionY, 70, 20).build());

        if (existingEntry != null) {
            addRenderableWidget(Button.builder(Component.literal("Delete"), btn -> delete())
                .bounds(left + 80, actionY, 70, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> {
            minecraft.setScreen(parent);
        }).bounds(left + 170, actionY, 70, 20).build());

        // Populate fields from existing entry
        if (existingEntry != null) {
            populateFromEntry();
        }

        // Apply pending area mark coordinates
        if (pendingC1 != null && pendingC2 != null) {
            locationType = "area";
            x1Box.setValue(String.valueOf(Math.min(pendingC1.getX(), pendingC2.getX())));
            z1Box.setValue(String.valueOf(Math.min(pendingC1.getZ(), pendingC2.getZ())));
            x2Box.setValue(String.valueOf(Math.max(pendingC1.getX(), pendingC2.getX())));
            z2Box.setValue(String.valueOf(Math.max(pendingC1.getZ(), pendingC2.getZ())));
            yBox.setValue(String.valueOf(pendingC1.getY()));
        }

        updateCategoryButtons();
        updateLocationVisibility();
    }

    private void populateFromEntry() {
        if (existingEntry.has("title")) titleBox.setValue(existingEntry.get("title").getAsString());
        if (existingEntry.has("description")) descBox.setValue(existingEntry.get("description").getAsString());
        if (existingEntry.has("content")) contentBox.setValue(existingEntry.get("content").getAsString());
        if (existingEntry.has("category")) selectedCategory = existingEntry.get("category").getAsString();
        if (existingEntry.has("tags") && existingEntry.get("tags").isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (var el : existingEntry.getAsJsonArray("tags")) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(el.getAsString());
            }
            tagsBox.setValue(sb.toString());
        }
        if (existingEntry.has("location") && existingEntry.get("location").isJsonObject()) {
            JsonObject loc = existingEntry.getAsJsonObject("location");
            String type = loc.has("type") ? loc.get("type").getAsString() : "point";
            locationType = type;
            if ("area".equals(type)) {
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
    }

    private EditBox addField(String label, int x, int y, int width) {
        EditBox box = new EditBox(font, x, y + 1, width, 16, Component.literal(label));
        box.setMaxLength(500);
        addRenderableWidget(box);
        return box;
    }

    private EditBox addSmallField(String label, int x, int y, int width) {
        EditBox box = new EditBox(font, x + font.width(label) + 2, y, width, 14, Component.literal(label));
        box.setMaxLength(10);
        addRenderableWidget(box);
        return box;
    }

    private void updateCategoryButtons() {
        for (int i = 0; i < CATEGORIES.length; i++) {
            categoryButtons[i].active = !CATEGORIES[i].equals(selectedCategory);
        }
    }

    private void updateLocationVisibility() {
        boolean isPoint = "point".equals(locationType);
        boolean isArea = "area".equals(locationType);
        xBox.visible = isPoint;
        yBox.visible = isPoint || isArea;
        zBox.visible = isPoint;
        radiusBox.visible = isPoint;
        x1Box.visible = isArea;
        z1Box.visible = isArea;
        x2Box.visible = isArea;
        z2Box.visible = isArea;
    }

    private void save() {
        JsonObject body = new JsonObject();
        body.addProperty("title", titleBox.getValue());
        body.addProperty("description", descBox.getValue());
        body.addProperty("content", contentBox.getValue());
        body.addProperty("category", selectedCategory);

        // Tags
        JsonArray tags = new JsonArray();
        for (String tag : tagsBox.getValue().split(",")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) tags.add(trimmed);
        }
        body.add("tags", tags);

        // Location
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

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(font, title, width / 2, 4, 0xFFFFFF);

        int left = width / 2 - 120;

        // Field labels
        int y = 20;
        guiGraphics.drawString(font, "Title:", left - font.width("Title:") - 4, y + 4, 0xAAAAAA);
        y += 24;
        guiGraphics.drawString(font, "Desc:", left - font.width("Desc:") - 4, y + 4, 0xAAAAAA);
        y += 24;
        guiGraphics.drawString(font, "Content:", left - font.width("Content:") - 4, y + 4, 0xAAAAAA);
        y += 24;
        guiGraphics.drawString(font, "Tags:", left - font.width("Tags:") - 4, y + 4, 0xAAAAAA);
        y += 28;
        guiGraphics.drawString(font, "Category:", left - font.width("Category:") - 4, y + 4, 0xAAAAAA);
        y += 22;
        guiGraphics.drawString(font, "Location:", left - font.width("Location:") - 4, y + 4, 0xAAAAAA);
        y += 20;

        // Location field labels
        if ("point".equals(locationType)) {
            guiGraphics.drawString(font, "X:", left, y + 2, 0xAAAAAA);
            guiGraphics.drawString(font, "Y:", left + 70, y + 2, 0xAAAAAA);
            guiGraphics.drawString(font, "Z:", left + 140, y + 2, 0xAAAAAA);
            guiGraphics.drawString(font, "R:", left + 210, y + 2, 0xAAAAAA);
        } else if ("area".equals(locationType)) {
            guiGraphics.drawString(font, "Y:", left + 70, y + 2, 0xAAAAAA);
            y += 20;
            guiGraphics.drawString(font, "X1:", left, y + 2, 0xAAAAAA);
            guiGraphics.drawString(font, "Z1:", left + 70, y + 2, 0xAAAAAA);
            guiGraphics.drawString(font, "X2:", left + 140, y + 2, 0xAAAAAA);
            guiGraphics.drawString(font, "Z2:", left + 210, y + 2, 0xAAAAAA);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
