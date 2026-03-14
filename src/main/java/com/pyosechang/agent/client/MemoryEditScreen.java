package com.pyosechang.agent.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pyosechang.agent.core.memory.MemoryEntry;
import com.pyosechang.agent.core.memory.MemoryManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Memory edit screen — two-column layout.
 * Left: Title, Desc, Content (with @mention picker).
 * Right: Category, Visibility, Location (category-dependent).
 *
 * Mention system:
 * - Content box displays @[Title] (human-readable)
 * - Stored as @memory:mXXX (machine-readable)
 * - @ key triggers picker popup with search EditBox
 * - Arrow keys navigate, Enter selects, Esc closes
 * - Title→ID mapping maintained for save/load conversion
 */
public class MemoryEditScreen extends Screen {
    private static final String[] CATEGORIES = {"storage", "facility", "area", "event", "skill"};
    private static final int[] CATEGORY_COLORS = {0x55FF55, 0xFFAA00, 0x55FFFF, 0xFFFF55, 0x5555FF};

    // @[Title] in display ↔ @memory:mXXX in storage
    private static final Pattern DISPLAY_REF = Pattern.compile("@\\[([^\\]]+)\\]");
    private static final Pattern STORAGE_REF = Pattern.compile("@memory:(m\\d+)");

    private final JsonObject existingEntry;
    private final MemoryListScreen parent;

    // Left column
    private EditBox titleBox;
    private MultiLineEditBox descBox;
    private MultiLineEditBox contentBox;

    // Right column
    private DropdownWidget categoryDropdown;
    private Button visibilityBtn;
    private Button locationCycleBtn;
    private EditBox xBox, yBox, zBox;           // point
    private EditBox x1Box, y1Box, z1Box;        // area row 1
    private EditBox x2Box, y2Box, z2Box;        // area row 2
    private Button currentPosBtn, markAreaBtn;

    private String selectedCategory = "event";
    private String locationType = "point";

    // m:n visibility — agent overlay
    private boolean globalScope = true;
    private boolean agentOverlayOpen = false;
    private Set<String> selectedAgents = new HashSet<>();
    private List<String> availableAgents = new ArrayList<>();

    private BlockPos pendingC1, pendingC2;

    // @ mention picker
    private boolean mentionOpen = false;
    private EditBox mentionSearchBox;
    private int mentionSelectedIdx = 0;
    private int mentionScrollOffset = 0;
    private List<JsonObject> allMemories = new ArrayList<>();
    private List<JsonObject> mentionFiltered = new ArrayList<>();
    // Title→ID mapping for display↔storage conversion
    private final Map<String, String> titleToId = new LinkedHashMap<>();
    private static final int MENTION_MAX_VISIBLE = 6;
    private static final int MENTION_ENTRY_H = 14;

    // Layout
    private int leftX, leftW, rightX, rightW;
    private int contentBoxY;
    private int agentBtnY;

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

        contentBoxY = y + 12;
        int contentH = Math.max(60, btnY - y - 36);
        contentBox = new MultiLineEditBox(font, leftX, contentBoxY, leftW, contentH,
            Component.literal(""), Component.literal("Content"));
        contentBox.setCharacterLimit(500);
        addRenderableWidget(contentBox);

        // Mention search box (hidden, only shown when picker is open)
        mentionSearchBox = new EditBox(font, leftX, contentBoxY - 20, leftW, 16, Component.empty());
        mentionSearchBox.setHint(Component.literal("Search memories..."));
        mentionSearchBox.setMaxLength(100);
        mentionSearchBox.visible = false;
        mentionSearchBox.setResponder(text -> {
            mentionSelectedIdx = 0;
            mentionScrollOffset = 0;
            filterMentions(text);
        });
        addRenderableWidget(mentionSearchBox);

        // === Right column ===
        y = 24;

        categoryDropdown = new DropdownWidget(font, rightX, y + 12, rightW, 16, Component.literal("Category"));
        for (int i = 0; i < CATEGORIES.length; i++) {
            String cat = CATEGORIES[i];
            String label = cat.substring(0, 1).toUpperCase() + cat.substring(1);
            categoryDropdown.addEntry(cat, label, CATEGORY_COLORS[i]);
        }
        categoryDropdown.setSelectedByKey(selectedCategory);
        categoryDropdown.setOnSelect(entry -> {
            selectedCategory = entry.key();
            updateLocationVisibility();
        });
        addRenderableWidget(categoryDropdown);
        y += 36;

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
        y += 50;

        // Location type cycle button
        locationCycleBtn = addRenderableWidget(Button.builder(
            Component.literal(locationType.substring(0, 1).toUpperCase() + locationType.substring(1)),
            btn -> {
                locationType = "point".equals(locationType) ? "area" : "point";
                btn.setMessage(Component.literal(locationType.substring(0, 1).toUpperCase() + locationType.substring(1)));
                updateLocationVisibility();
            }
        ).bounds(rightX, y + GuiLayout.LABEL_GAP, rightW, 16).build());
        y += GuiLayout.ROW_H;

        int smallW = (rightW - 8) / 3;

        // Point: X Y Z (label + field, one row)
        xBox = createCoordField(rightX, y + GuiLayout.LABEL_GAP, smallW);
        yBox = createCoordField(rightX + smallW + 4, y + GuiLayout.LABEL_GAP, smallW);
        zBox = createCoordField(rightX + (smallW + 4) * 2, y + GuiLayout.LABEL_GAP, smallW);

        // Area row 1: X1 Y1 Z1 (label + field)
        x1Box = createCoordField(rightX, y + GuiLayout.LABEL_GAP, smallW);
        y1Box = createCoordField(rightX + smallW + 4, y + GuiLayout.LABEL_GAP, smallW);
        z1Box = createCoordField(rightX + (smallW + 4) * 2, y + GuiLayout.LABEL_GAP, smallW);

        // Area row 2: X2 Y2 Z2 (label + field)
        x2Box = createCoordField(rightX, y + GuiLayout.ROW_H + GuiLayout.LABEL_GAP, smallW);
        y2Box = createCoordField(rightX + smallW + 4, y + GuiLayout.ROW_H + GuiLayout.LABEL_GAP, smallW);
        z2Box = createCoordField(rightX + (smallW + 4) * 2, y + GuiLayout.ROW_H + GuiLayout.LABEL_GAP, smallW);

        // Utility buttons — after 2 rows of coords
        int utilY = y + GuiLayout.ROW_H * 2 + 4;
        int utilBtnW = (rightW - 4) / 2;
        currentPosBtn = addRenderableWidget(Button.builder(Component.literal("Current Pos"), btn -> {
            if (minecraft.player != null) {
                BlockPos pos = minecraft.player.blockPosition();
                if ("area".equals(locationType)) {
                    x1Box.setValue(String.valueOf(pos.getX()));
                    y1Box.setValue(String.valueOf(pos.getY()));
                    z1Box.setValue(String.valueOf(pos.getZ()));
                } else {
                    xBox.setValue(String.valueOf(pos.getX()));
                    yBox.setValue(String.valueOf(pos.getY()));
                    zBox.setValue(String.valueOf(pos.getZ()));
                }
            }
        }).bounds(rightX, utilY, utilBtnW, 16).build());
        markAreaBtn = addRenderableWidget(Button.builder(Component.literal("Mark Area"), btn -> {
            AreaMarkHandler.start((c1, c2) -> {
                minecraft.setScreen(new MemoryEditScreen(existingEntry, parent, "area", c1, c2));
            });
        }).bounds(rightX + utilBtnW + 4, utilY, utilBtnW, 16).build());

        // === Bottom buttons ===
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
        // Sync cycle button text after populate
        locationCycleBtn.setMessage(Component.literal(
            locationType.substring(0, 1).toUpperCase() + locationType.substring(1)));

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
            y1Box.setValue(String.valueOf(Math.min(pendingC1.getY(), pendingC2.getY())));
            z1Box.setValue(String.valueOf(Math.min(pendingC1.getZ(), pendingC2.getZ())));
            x2Box.setValue(String.valueOf(Math.max(pendingC1.getX(), pendingC2.getX())));
            y2Box.setValue(String.valueOf(Math.max(pendingC1.getY(), pendingC2.getY())));
            z2Box.setValue(String.valueOf(Math.max(pendingC1.getZ(), pendingC2.getZ())));
        }

        loadAgentNames();
        loadAllMemories();
        updateLocationVisibility();
    }

    private EditBox createCoordField(int x, int y, int w) {
        EditBox box = new EditBox(font, x, y, w, 14, Component.empty());
        box.setMaxLength(10);
        addRenderableWidget(box);
        return box;
    }

    // --- Data load ---

    private void populateFromEntry() {
        if (existingEntry.has("title")) titleBox.setValue(existingEntry.get("title").getAsString());
        if (existingEntry.has("description")) descBox.setValue(existingEntry.get("description").getAsString());
        if (existingEntry.has("content")) {
            // Content will be converted to display format after memories load
            // Store raw for now, convert in loadAllMemories callback
            contentBox.setValue(existingEntry.get("content").getAsString());
        }
        if (existingEntry.has("category")) {
            selectedCategory = existingEntry.get("category").getAsString();
            categoryDropdown.setSelectedByKey(selectedCategory);
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
                if (loc.has("y1")) y1Box.setValue(String.valueOf(loc.get("y1").getAsInt()));
                else if (loc.has("y")) y1Box.setValue(String.valueOf(loc.get("y").getAsInt()));
                if (loc.has("z1")) z1Box.setValue(String.valueOf(loc.get("z1").getAsInt()));
                if (loc.has("x2")) x2Box.setValue(String.valueOf(loc.get("x2").getAsInt()));
                if (loc.has("y2")) y2Box.setValue(String.valueOf(loc.get("y2").getAsInt()));
                else if (loc.has("y")) y2Box.setValue(String.valueOf(loc.get("y").getAsInt()));
                if (loc.has("z2")) z2Box.setValue(String.valueOf(loc.get("z2").getAsInt()));
            } else {
                if (loc.has("x")) xBox.setValue(String.valueOf(loc.get("x").getAsInt()));
                if (loc.has("y")) yBox.setValue(String.valueOf(loc.get("y").getAsInt()));
                if (loc.has("z")) zBox.setValue(String.valueOf(loc.get("z").getAsInt()));
            }
        }
        if (visibilityBtn != null) {
            visibilityBtn.setMessage(Component.literal(globalScope ? "Global (all agents)" : "Specific agents"));
        }
    }

    private void loadAgentNames() {
        try {
            java.nio.file.Path agentsDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve(".agent/agents");
            if (java.nio.file.Files.isDirectory(agentsDir)) {
                List<String> names = new ArrayList<>();
                try (var dirs = java.nio.file.Files.list(agentsDir)) {
                    dirs.filter(java.nio.file.Files::isDirectory).forEach(dir ->
                        names.add(dir.getFileName().toString()));
                }
                availableAgents = names;
            }
        } catch (Exception ignored) {}
    }

    private void loadAllMemories() {
        MemoryManager mm = MemoryManager.getInstance();
        List<MemoryEntry> all = mm.search("", null, "all");

        List<JsonObject> entries = new ArrayList<>();
        Map<String, String> idToTitle = new HashMap<>();
        for (MemoryEntry e : all) {
            JsonObject mem = mm.entryToJson(e);
            entries.add(mem);
            idToTitle.put(e.getId(), e.getTitle() != null ? e.getTitle() : e.getId());
            titleToId.put(e.getTitle() != null ? e.getTitle() : e.getId(), e.getId());
        }
        allMemories = entries;

        // Convert content from storage format (@memory:mXXX) to display format (@[Title])
        String raw = contentBox.getValue();
        String display = storageToDisplay(raw, idToTitle);
        if (!display.equals(raw)) {
            contentBox.setValue(display);
        }
    }

    // --- @[Title] ↔ @memory:mXXX conversion ---

    /** Storage → Display: @memory:m001 → @[Wheat Farm] */
    private String storageToDisplay(String text, Map<String, String> idToTitle) {
        if (text == null) return "";
        Matcher m = STORAGE_REF.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String id = m.group(1);
            String title = idToTitle.getOrDefault(id, id);
            m.appendReplacement(sb, "@[" + Matcher.quoteReplacement(title) + "]");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Display → Storage: @[Wheat Farm] → @memory:m001 */
    private String displayToStorage(String text) {
        if (text == null) return "";
        Matcher m = DISPLAY_REF.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String title = m.group(1);
            String id = titleToId.getOrDefault(title, title);
            m.appendReplacement(sb, "@memory:" + Matcher.quoteReplacement(id));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // --- @ mention picker ---

    private void openMentionPicker() {
        mentionOpen = true;
        mentionSelectedIdx = 0;
        mentionScrollOffset = 0;
        mentionSearchBox.setValue("");
        mentionSearchBox.visible = true;
        mentionSearchBox.setFocused(true);
        setFocused(mentionSearchBox);
        filterMentions("");
    }

    private void closeMentionPicker() {
        mentionOpen = false;
        mentionSearchBox.visible = false;
        mentionSearchBox.setFocused(false);
        setFocused(contentBox);
    }

    private void filterMentions(String query) {
        mentionFiltered = new ArrayList<>();
        String q = query.toLowerCase();
        String selfId = existingEntry != null && existingEntry.has("id")
            ? existingEntry.get("id").getAsString() : null;

        for (JsonObject mem : allMemories) {
            String id = mem.has("id") ? mem.get("id").getAsString() : "";
            if (id.equals(selfId)) continue;
            if ("schedule".equals(mem.has("category") ? mem.get("category").getAsString() : "")) continue;

            if (q.isEmpty()) {
                mentionFiltered.add(mem);
            } else {
                String title = mem.has("title") ? mem.get("title").getAsString().toLowerCase() : "";
                if (id.toLowerCase().contains(q) || title.contains(q)) {
                    mentionFiltered.add(mem);
                }
            }
            if (mentionFiltered.size() >= 30) break;
        }
    }

    private void selectMention() {
        if (mentionFiltered.isEmpty()) return;
        int idx = Math.min(mentionSelectedIdx, mentionFiltered.size() - 1);
        JsonObject mem = mentionFiltered.get(idx);
        String id = mem.has("id") ? mem.get("id").getAsString() : "";
        String title = mem.has("title") ? mem.get("title").getAsString() : id;

        // Insert @[Title] at end of content (we consumed @ so it's not in the text)
        String text = contentBox.getValue();
        contentBox.setValue(text + "@[" + title + "] ");
        // Ensure mapping
        titleToId.put(title, id);

        closeMentionPicker();
    }

    // --- Location ---

    private boolean categoryHasLocation() {
        return "storage".equals(selectedCategory) || "facility".equals(selectedCategory)
            || "area".equals(selectedCategory) || "event".equals(selectedCategory);
    }

    private void updateLocationVisibility() {
        boolean hasLoc = categoryHasLocation();
        boolean isPoint = hasLoc && "point".equals(locationType);
        boolean isArea = hasLoc && "area".equals(locationType);
        locationCycleBtn.visible = hasLoc;
        xBox.visible = isPoint; yBox.visible = isPoint; zBox.visible = isPoint;
        x1Box.visible = isArea; y1Box.visible = isArea; z1Box.visible = isArea;
        x2Box.visible = isArea; y2Box.visible = isArea; z2Box.visible = isArea;
        currentPosBtn.visible = hasLoc;
        markAreaBtn.visible = hasLoc;
    }

    // --- Save / Delete ---

    private void save() {
        JsonObject body = new JsonObject();
        body.addProperty("title", titleBox.getValue());
        body.addProperty("description", descBox.getValue());
        // Convert display format back to storage format
        body.addProperty("content", displayToStorage(contentBox.getValue()));
        body.addProperty("category", selectedCategory);

        if (categoryHasLocation()) {
            JsonObject loc = new JsonObject();
            loc.addProperty("type", locationType);
            if ("area".equals(locationType)) {
                loc.addProperty("x1", parseDouble(x1Box.getValue()));
                loc.addProperty("y1", parseDouble(y1Box.getValue()));
                loc.addProperty("z1", parseDouble(z1Box.getValue()));
                loc.addProperty("x2", parseDouble(x2Box.getValue()));
                loc.addProperty("y2", parseDouble(y2Box.getValue()));
                loc.addProperty("z2", parseDouble(z2Box.getValue()));
            } else {
                loc.addProperty("x", parseDouble(xBox.getValue()));
                loc.addProperty("y", parseDouble(yBox.getValue()));
                loc.addProperty("z", parseDouble(zBox.getValue()));
            }
            body.add("location", loc);
        }

        JsonArray visibleTo = new JsonArray();
        if (!globalScope) {
            for (String agent : selectedAgents) visibleTo.add(agent);
        }
        body.add("visible_to", visibleTo);

        // Normalize visible_to → visibleTo for createFromJson
        if (body.has("visible_to") && !body.has("visibleTo")) {
            body.add("visibleTo", body.get("visible_to"));
        }

        MemoryManager mm = MemoryManager.getInstance();
        if (existingEntry != null) {
            String id = existingEntry.get("id").getAsString();
            mm.update(id, body);
        } else {
            mm.createFromJson(body);
        }
        minecraft.setScreen(parent);
    }

    private void delete() {
        if (existingEntry == null) return;
        String id = existingEntry.get("id").getAsString();
        MemoryManager.getInstance().delete(id);
        minecraft.setScreen(parent);
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
        g.drawString(font, "Content  (@ to ref)", leftX, y, 0x999999);

        // === Right column labels ===
        y = 24;
        g.drawString(font, "Category", rightX, y, 0x999999);
        y += 36;
        g.drawString(font, "Visibility", rightX, y, 0x999999);

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

        if (categoryHasLocation()) {
            g.drawString(font, "Location", rightX, y, 0x999999);
            y += GuiLayout.ROW_H; // after cycle button
            int smallW = (rightW - 8) / 3;
            if ("point".equals(locationType)) {
                g.drawString(font, "X", rightX + 2, y, 0x777777);
                g.drawString(font, "Y", rightX + smallW + 6, y, 0x777777);
                g.drawString(font, "Z", rightX + (smallW + 4) * 2 + 2, y, 0x777777);
            } else if ("area".equals(locationType)) {
                g.drawString(font, "X1", rightX + 2, y, 0x777777);
                g.drawString(font, "Y1", rightX + smallW + 6, y, 0x777777);
                g.drawString(font, "Z1", rightX + (smallW + 4) * 2 + 2, y, 0x777777);
                g.drawString(font, "X2", rightX + 2, y + GuiLayout.ROW_H, 0x777777);
                g.drawString(font, "Y2", rightX + smallW + 6, y + GuiLayout.ROW_H, 0x777777);
                g.drawString(font, "Z2", rightX + (smallW + 4) * 2 + 2, y + GuiLayout.ROW_H, 0x777777);
            }
        }

        super.render(g, mouseX, mouseY, partialTick);

        // === Overlays ===
        categoryDropdown.renderDropdown(g, mouseX, mouseY);

        if (agentOverlayOpen && !globalScope && !availableAgents.isEmpty()) {
            renderAgentOverlay(g, mouseX, mouseY);
        }
        if (mentionOpen && !mentionFiltered.isEmpty()) {
            renderMentionOverlay(g, mouseX, mouseY);
        }
    }

    private void renderAgentOverlay(GuiGraphics g, int mouseX, int mouseY) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        int oX = rightX, oY = agentBtnY + 16;
        int oH = availableAgents.size() * 14 + 4;
        g.fill(oX - 1, oY - 1, oX + rightW + 1, oY + oH + 1, 0xFF808080);
        g.fill(oX, oY, oX + rightW, oY + oH, 0xFF1A1A1A);
        for (int i = 0; i < availableAgents.size(); i++) {
            String agent = availableAgents.get(i);
            boolean checked = selectedAgents.contains(agent);
            int ay = oY + 2 + i * 14;
            boolean hovered = mouseX >= oX && mouseX < oX + rightW && mouseY >= ay && mouseY < ay + 13;
            if (hovered) g.fill(oX, ay - 1, oX + rightW, ay + 12, 0xFF333333);
            int cbX = oX + 4;
            g.fill(cbX, ay, cbX + 10, ay + 10, 0xFF222222);
            g.fill(cbX, ay, cbX + 10, ay + 1, 0xFF555555);
            g.fill(cbX, ay + 9, cbX + 10, ay + 10, 0xFF555555);
            g.fill(cbX, ay, cbX + 1, ay + 10, 0xFF555555);
            g.fill(cbX + 9, ay, cbX + 10, ay + 10, 0xFF555555);
            if (checked) g.fill(cbX + 2, ay + 2, cbX + 8, ay + 8, 0xFF55FF55);
            g.drawString(font, agent, cbX + 14, ay + 1, checked ? 0xFFFFFF : 0x999999);
            if (i < availableAgents.size() - 1)
                g.fill(oX + 2, ay + 12, oX + rightW - 2, ay + 13, 0xFF2A2A2A);
        }
        g.pose().popPose();
    }

    private void renderMentionOverlay(GuiGraphics g, int mouseX, int mouseY) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);

        int visible = Math.min(mentionFiltered.size() - mentionScrollOffset, MENTION_MAX_VISIBLE);
        int oW = leftW;
        int oH = visible * MENTION_ENTRY_H + 4;
        int oX = leftX;
        // Below the search box
        int oY = contentBoxY - 20 + 18;

        g.fill(oX - 1, oY - 1, oX + oW + 1, oY + oH + 1, 0xFF5577AA);
        g.fill(oX, oY, oX + oW, oY + oH, 0xFF111122);

        for (int i = 0; i < visible; i++) {
            int idx = i + mentionScrollOffset;
            JsonObject mem = mentionFiltered.get(idx);
            String id = mem.has("id") ? mem.get("id").getAsString() : "";
            String memTitle = mem.has("title") ? mem.get("title").getAsString() : "";
            String cat = mem.has("category") ? mem.get("category").getAsString() : "";

            int ey = oY + 2 + i * MENTION_ENTRY_H;
            boolean isSelected = (idx == mentionSelectedIdx);
            boolean hovered = mouseX >= oX && mouseX < oX + oW && mouseY >= ey && mouseY < ey + MENTION_ENTRY_H;

            if (isSelected) g.fill(oX, ey - 1, oX + oW, ey + MENTION_ENTRY_H - 2, 0xFF334477);
            else if (hovered) g.fill(oX, ey - 1, oX + oW, ey + MENTION_ENTRY_H - 2, 0xFF223355);

            int tx = oX + 4;
            g.drawString(font, id, tx, ey + 1, 0x77AAFF);
            tx += font.width(id) + 4;
            g.drawString(font, "[" + cat + "]", tx, ey + 1, getCatColor(cat));
            tx += font.width("[" + cat + "]") + 4;

            int remaining = oX + oW - tx - 4;
            String truncTitle = memTitle;
            if (font.width(truncTitle) > remaining) {
                while (font.width(truncTitle + "..") > remaining && truncTitle.length() > 0)
                    truncTitle = truncTitle.substring(0, truncTitle.length() - 1);
                truncTitle += "..";
            }
            g.drawString(font, truncTitle, tx, ey + 1, isSelected ? 0xFFFFFF : 0xDDDDDD);
        }

        if (mentionFiltered.size() > MENTION_MAX_VISIBLE) {
            int total = mentionFiltered.size();
            int barH = Math.max(4, visible * oH / total);
            int barY = oY + (mentionScrollOffset * (oH - barH)) / Math.max(1, total - MENTION_MAX_VISIBLE);
            g.fill(oX + oW - 3, barY, oX + oW - 1, barY + barH, 0x60FFFFFF);
        }

        g.pose().popPose();
    }

    private int getCatColor(String cat) {
        return switch (cat) {
            case "storage" -> 0x55FF55;
            case "facility" -> 0xFFAA00;
            case "area" -> 0x55FFFF;
            case "event" -> 0xFFFF55;
            case "skill" -> 0x5555FF;
            default -> 0xAAAAAA;
        };
    }

    // === Input handling ===

    @Override
    public boolean charTyped(char c, int modifiers) {
        // @ key while content is focused → open mention picker (consume the key)
        if (c == '@' && contentBox.isFocused() && !mentionOpen) {
            openMentionPicker();
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (mentionOpen) {
            // Arrow up
            if (keyCode == 265) {
                mentionSelectedIdx = Math.max(0, mentionSelectedIdx - 1);
                // Scroll to keep selection visible
                if (mentionSelectedIdx < mentionScrollOffset)
                    mentionScrollOffset = mentionSelectedIdx;
                return true;
            }
            // Arrow down
            if (keyCode == 264) {
                mentionSelectedIdx = Math.min(mentionFiltered.size() - 1, mentionSelectedIdx + 1);
                if (mentionSelectedIdx >= mentionScrollOffset + MENTION_MAX_VISIBLE)
                    mentionScrollOffset = mentionSelectedIdx - MENTION_MAX_VISIBLE + 1;
                return true;
            }
            // Enter → select
            if (keyCode == 257 || keyCode == 335) {
                selectMention();
                return true;
            }
            // Escape → close
            if (keyCode == 256) {
                closeMentionPicker();
                return true;
            }
            // Tab → select (like autocomplete)
            if (keyCode == 258) {
                selectMention();
                return true;
            }
            // Let other keys (typing) go to the search box
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        // Escape closes agent overlay
        if (keyCode == 256 && agentOverlayOpen) {
            agentOverlayOpen = false;
            if (selectedAgents.isEmpty()) {
                globalScope = true;
                visibilityBtn.setMessage(Component.literal("Global (all agents)"));
            }
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Mention overlay clicks
            if (mentionOpen && !mentionFiltered.isEmpty()) {
                int visible = Math.min(mentionFiltered.size() - mentionScrollOffset, MENTION_MAX_VISIBLE);
                int oW = leftW;
                int oX = leftX;
                int oY = contentBoxY - 20 + 18;

                for (int i = 0; i < visible; i++) {
                    int idx = i + mentionScrollOffset;
                    int ey = oY + 2 + i * MENTION_ENTRY_H;
                    if (mouseX >= oX && mouseX < oX + oW && mouseY >= ey && mouseY < ey + MENTION_ENTRY_H) {
                        mentionSelectedIdx = idx;
                        selectMention();
                        return true;
                    }
                }

                // Click on search box → let it handle
                if (mouseX >= mentionSearchBox.getX() && mouseX < mentionSearchBox.getX() + leftW
                    && mouseY >= mentionSearchBox.getY() && mouseY < mentionSearchBox.getY() + 16) {
                    return super.mouseClicked(mouseX, mouseY, button);
                }

                // Click outside → close
                closeMentionPicker();
                return true;
            }

            // Category dropdown
            if (categoryDropdown.isOpen()) {
                boolean handled = categoryDropdown.mouseClicked(mouseX, mouseY, button);
                if (handled) return true;
            }

            // Agent overlay
            if (agentOverlayOpen && !globalScope && !availableAgents.isEmpty()) {
                int oX = rightX, oY = agentBtnY + 16;
                for (int i = 0; i < availableAgents.size(); i++) {
                    String agent = availableAgents.get(i);
                    int ay = oY + 2 + i * 14;
                    if (mouseX >= oX && mouseX < oX + rightW && mouseY >= ay && mouseY < ay + 13) {
                        if (selectedAgents.contains(agent)) selectedAgents.remove(agent);
                        else selectedAgents.add(agent);
                        return true;
                    }
                }
                if (!(mouseX >= rightX && mouseX < rightX + rightW
                    && mouseY >= agentBtnY && mouseY < agentBtnY + 16)) {
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
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mentionOpen && !mentionFiltered.isEmpty() && mentionFiltered.size() > MENTION_MAX_VISIBLE) {
            mentionScrollOffset -= (int) delta;
            mentionScrollOffset = Math.max(0, Math.min(mentionScrollOffset, mentionFiltered.size() - MENTION_MAX_VISIBLE));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
