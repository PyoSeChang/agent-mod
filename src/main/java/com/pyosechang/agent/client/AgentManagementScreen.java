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
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent management screen.
 * Layout: [Agent List | Form Fields | Tools]
 * No title bar. Agent list full height. Two-column right panel.
 */
public class AgentManagementScreen extends Screen {
    private static final int LEFT_W = 110;
    private static final int ENTRY_H = 22;
    private static final int LIST_TOP = 36;

    // Agent list
    private List<JsonObject> agents = new ArrayList<>();
    private List<JsonObject> filteredAgents = new ArrayList<>();
    private int selectedIndex = -1;
    private int listScroll = 0;
    private EditBox searchBox;

    // Form fields (middle column)
    private EditBox nameBox;
    private MultiLineEditBox roleBox;
    private MultiLineEditBox personalityBox;

    // Tools (right column)
    private List<String> availableTools = new ArrayList<>();
    private Set<String> selectedTools = new HashSet<>();
    private int toolScroll = 0;

    // Buttons
    private Button createBtn, deleteBtn, spawnBtn, despawnBtn, saveBtn;

    // State
    private String selectedAgentName = null;
    private boolean selectedSpawned = false;
    private boolean createMode = false;

    // Layout computed values
    private int midX, midW, toolX, toolW, toolListY;

    // Tooltip state (deferred rendering)
    private String pendingTooltipTool = null;
    private int pendingTooltipX, pendingTooltipY;

    public AgentManagementScreen() {
        super(Component.translatable("gui.agent.management.title"));
    }

    @Override
    protected void init() {
        // Layout columns — center form+tools in right area
        int leftEnd = LEFT_W;
        int rightArea = width - leftEnd;
        int gap = 16;
        midW = Math.min((rightArea - gap * 3) / 2, 280);
        toolW = Math.min((rightArea - gap * 3) / 2, 220);
        int totalContentW = midW + gap + toolW;
        midX = leftEnd + (rightArea - totalContentW) / 2;
        toolX = midX + midW + gap;

        // Agent list search box
        searchBox = new EditBox(font, 4, 18, LEFT_W - 8, 14, Component.translatable("gui.agent.management.search"));
        searchBox.setHint(Component.translatable("gui.agent.management.search_hint"));
        searchBox.setResponder(text -> filterAgents());
        addRenderableWidget(searchBox);

        // Vertically center form: name(28) + role(100+12) + pers(120+12) = ~272
        int formH = 28 + 12 + 100 + 14 + 120;
        int formTop = Math.max(20, (height - 30 - formH) / 2);
        int y = formTop;

        // Name field
        nameBox = new EditBox(font, midX, y + 12, midW, 16, Component.translatable("gui.agent.management.name"));
        nameBox.setMaxLength(32);
        nameBox.setHint(Component.translatable("gui.agent.management.name_hint"));
        nameBox.setEditable(true);
        addRenderableWidget(nameBox);
        y += 40;

        // Role textarea
        int roleH = 100;
        roleBox = new MultiLineEditBox(font, midX, y + 12, midW, roleH,
            Component.literal(""), Component.translatable("gui.agent.management.role"));
        roleBox.setCharacterLimit(300);
        addRenderableWidget(roleBox);
        y += roleH + 22;

        // Personality textarea
        int persH = 120;
        personalityBox = new MultiLineEditBox(font, midX, y + 12, midW, persH,
            Component.literal(""), Component.translatable("gui.agent.management.personality"));
        personalityBox.setCharacterLimit(500);
        addRenderableWidget(personalityBox);

        // Bottom buttons
        int btnY = height - 26;
        int btnW = 58;
        int btnGap = 5;
        int totalBtnW = btnW * 5 + btnGap * 4;
        int bx = width / 2 - totalBtnW / 2;

        createBtn = addRenderableWidget(Button.builder(Component.translatable("gui.agent.management.create"), btn -> {
            if (createMode) doCreate();
            else enterCreateMode();
        }).bounds(bx, btnY, btnW, 20).build());
        bx += btnW + btnGap;

        deleteBtn = addRenderableWidget(Button.builder(Component.translatable("gui.agent.management.delete"), btn -> doDelete())
            .bounds(bx, btnY, btnW, 20).build());
        bx += btnW + btnGap;

        spawnBtn = addRenderableWidget(Button.builder(Component.translatable("gui.agent.management.spawn"), btn -> doSpawn())
            .bounds(bx, btnY, btnW, 20).build());
        bx += btnW + btnGap;

        despawnBtn = addRenderableWidget(Button.builder(Component.translatable("gui.agent.management.despawn"), btn -> doDespawn())
            .bounds(bx, btnY, btnW, 20).build());
        bx += btnW + btnGap;

        saveBtn = addRenderableWidget(Button.builder(Component.translatable("gui.agent.management.save"), btn -> doSave())
            .bounds(bx, btnY, btnW, 20).build());

        updateVisibility();
        updateButtons();
        refreshAgentList();
        loadAvailableTools();
    }

    private void loadAvailableTools() {
        BridgeClient.get("/agent/_tools_/persona").thenAccept(result -> {
            if (result.has("available_tools")) {
                List<String> tools = new ArrayList<>();
                for (JsonElement el : result.getAsJsonArray("available_tools")) {
                    tools.add(el.getAsString());
                }
                Minecraft.getInstance().tell(() -> {
                    availableTools = tools;
                    if (createMode && selectedTools.isEmpty()) {
                        selectedTools.addAll(tools);
                    }
                });
            }
        });
    }

    private void filterAgents() {
        String query = searchBox != null ? searchBox.getValue().toLowerCase() : "";
        filteredAgents.clear();
        for (JsonObject agent : agents) {
            String name = agent.get("name").getAsString().toLowerCase();
            if (query.isEmpty() || name.contains(query)) {
                filteredAgents.add(agent);
            }
        }
    }

    private void updateVisibility() {
        boolean show = selectedAgentName != null || createMode;
        nameBox.visible = show;
        roleBox.visible = show;
        personalityBox.visible = show;
        nameBox.setEditable(true);
    }

    private void updateButtons() {
        boolean hasAgent = selectedAgentName != null && !createMode;
        deleteBtn.active = hasAgent && !selectedSpawned;
        spawnBtn.active = hasAgent && !selectedSpawned;
        despawnBtn.active = hasAgent && selectedSpawned;
        saveBtn.active = hasAgent;
        createBtn.setMessage(Component.translatable(createMode ? "gui.agent.management.confirm" : "gui.agent.management.create"));
    }

    private void refreshAgentList() {
        BridgeClient.get("/agents/list").thenAccept(result -> {
            if (result.has("agents")) {
                List<JsonObject> list = new ArrayList<>();
                for (JsonElement el : result.getAsJsonArray("agents")) list.add(el.getAsJsonObject());
                Minecraft.getInstance().tell(() -> {
                    agents = list;
                    filterAgents();
                    if (selectedAgentName != null) {
                        selectedIndex = -1;
                        for (int i = 0; i < filteredAgents.size(); i++) {
                            if (selectedAgentName.equals(filteredAgents.get(i).get("name").getAsString())) {
                                selectedIndex = i;
                                break;
                            }
                        }
                        if (selectedIndex < 0 && !createMode) clearSelection();
                    }
                });
            }
        });
    }

    private void selectAgent(int index) {
        if (index < 0 || index >= filteredAgents.size()) return;
        createMode = false;
        selectedIndex = index;
        JsonObject agent = filteredAgents.get(index);
        selectedAgentName = agent.get("name").getAsString();
        selectedSpawned = agent.has("spawned") && agent.get("spawned").getAsBoolean();

        nameBox.setValue(selectedAgentName);

        BridgeClient.get("/agent/" + selectedAgentName + "/persona").thenAccept(result -> {
            Minecraft.getInstance().tell(() -> {
                roleBox.setValue(result.has("role") ? result.get("role").getAsString() : "");
                personalityBox.setValue(result.has("personality") ? result.get("personality").getAsString() : "");

                selectedTools.clear();
                availableTools.clear();
                if (result.has("available_tools")) {
                    for (JsonElement el : result.getAsJsonArray("available_tools")) {
                        String t = el.getAsString();
                        availableTools.add(t);
                        selectedTools.add(t);
                    }
                }
                if (result.has("tools") && result.getAsJsonArray("tools").size() > 0) {
                    selectedTools.clear();
                    for (JsonElement el : result.getAsJsonArray("tools")) {
                        selectedTools.add(el.getAsString());
                    }
                }
                selectedSpawned = result.has("spawned") && result.get("spawned").getAsBoolean();
                toolScroll = 0;
                updateVisibility();
                updateButtons();
            });
        });
        updateVisibility();
        updateButtons();
    }

    private void clearSelection() {
        selectedIndex = -1;
        selectedAgentName = null;
        selectedSpawned = false;
        createMode = false;
        nameBox.setValue("");
        roleBox.setValue("");
        personalityBox.setValue("");
        selectedTools.clear();
        updateVisibility();
        updateButtons();
    }

    private void enterCreateMode() {
        createMode = true;
        selectedIndex = -1;
        selectedAgentName = null;
        selectedSpawned = false;
        nameBox.setValue("");
        nameBox.setFocused(true);
        roleBox.setValue("");
        personalityBox.setValue("");

        selectedTools.clear();
        selectedTools.addAll(availableTools);

        updateVisibility();
        updateButtons();
    }

    private void doCreate() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) return;

        JsonObject body = new JsonObject();
        body.addProperty("role", roleBox.getValue().isEmpty() ? I18n.get("gui.agent.management.default_role") : roleBox.getValue());
        body.addProperty("personality", personalityBox.getValue().isEmpty() ? I18n.get("gui.agent.management.default_personality") : personalityBox.getValue());
        JsonArray tools = new JsonArray();
        if (selectedTools.size() < availableTools.size()) {
            for (String t : selectedTools) tools.add(t);
        }
        body.add("tools", tools);
        body.add("acquaintances", new JsonArray());

        BridgeClient.post("/agent/" + name + "/persona", body).thenAccept(result -> {
            Minecraft.getInstance().tell(() -> {
                createMode = false;
                selectedAgentName = name;
                refreshAgentList();
                BridgeClient.get("/agents/list").thenAccept(r2 -> {
                    if (r2.has("agents")) {
                        List<JsonObject> list = new ArrayList<>();
                        for (JsonElement el : r2.getAsJsonArray("agents")) list.add(el.getAsJsonObject());
                        Minecraft.getInstance().tell(() -> {
                            agents = list;
                            for (int i = 0; i < agents.size(); i++) {
                                if (name.equals(agents.get(i).get("name").getAsString())) {
                                    selectAgent(i);
                                    return;
                                }
                            }
                        });
                    }
                });
            });
        });
    }

    private void doDelete() {
        if (selectedAgentName == null || selectedSpawned) return;
        JsonObject body = new JsonObject();
        body.addProperty("name", selectedAgentName);
        BridgeClient.post("/agents/delete", body).thenAccept(result -> {
            Minecraft.getInstance().tell(() -> { clearSelection(); refreshAgentList(); });
        });
    }

    private void doSpawn() {
        if (selectedAgentName == null || selectedSpawned || minecraft.player == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("name", selectedAgentName);
        body.addProperty("x", minecraft.player.blockPosition().getX());
        body.addProperty("y", minecraft.player.blockPosition().getY());
        body.addProperty("z", minecraft.player.blockPosition().getZ());
        BridgeClient.post("/agent/" + selectedAgentName + "/spawn", body).thenAccept(result -> {
            Minecraft.getInstance().tell(() -> { selectedSpawned = true; updateButtons(); refreshAgentList(); });
        });
    }

    private void doDespawn() {
        if (selectedAgentName == null || !selectedSpawned) return;
        BridgeClient.post("/agent/" + selectedAgentName + "/despawn", new JsonObject()).thenAccept(result -> {
            Minecraft.getInstance().tell(() -> { selectedSpawned = false; updateButtons(); refreshAgentList(); });
        });
    }

    private void doSave() {
        if (selectedAgentName == null) return;
        String newName = nameBox.getValue().trim();
        if (newName.isEmpty()) return;

        JsonObject body = new JsonObject();
        body.addProperty("role", roleBox.getValue());
        body.addProperty("personality", personalityBox.getValue());
        JsonArray tools = new JsonArray();
        if (selectedTools.size() < availableTools.size()) {
            for (String t : selectedTools) tools.add(t);
        }
        body.add("tools", tools);
        body.add("acquaintances", new JsonArray());

        boolean renamed = !newName.equals(selectedAgentName);

        if (renamed && !selectedSpawned) {
            // Create new agent with new name, then delete old
            String oldName = selectedAgentName;
            BridgeClient.post("/agent/" + newName + "/persona", body).thenAccept(result -> {
                JsonObject delBody = new JsonObject();
                delBody.addProperty("name", oldName);
                BridgeClient.post("/agents/delete", delBody).thenAccept(r2 -> {
                    Minecraft.getInstance().tell(() -> {
                        selectedAgentName = newName;
                        refreshAgentList();
                    });
                });
            });
        } else {
            // Normal save (or spawned agent can't rename)
            BridgeClient.post("/agent/" + selectedAgentName + "/persona", body).thenAccept(result -> {
                Minecraft.getInstance().tell(this::refreshAgentList);
            });
        }
    }

    // === Rendering ===

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        // Title bar
        g.fill(0, 0, width, 14, 0x80000000);
        g.drawCenteredString(font, title, width / 2, 3, 0xFFFFFF);

        int leftEnd = LEFT_W;

        // === Left panel: agent list (full height) ===
        g.fill(0, 14, leftEnd, height, 0x80000000);
        g.drawString(font, I18n.get("gui.agent.management.agents_count", filteredAgents.size()), 6, 4, 0xCCCCCC);
        g.fill(4, LIST_TOP - 2, leftEnd - 4, LIST_TOP - 1, 0x30FFFFFF);

        int listBottom = height - 30;
        int visibleEntries = (listBottom - LIST_TOP) / ENTRY_H;
        int maxScroll = Math.max(0, filteredAgents.size() - visibleEntries);
        listScroll = Math.min(listScroll, maxScroll);

        if (filteredAgents.isEmpty()) {
            g.drawString(font, I18n.get("gui.agent.management.no_agents"), 8, LIST_TOP + 4, 0x606060);
        }

        for (int i = 0; i < visibleEntries && i + listScroll < filteredAgents.size(); i++) {
            int idx = i + listScroll;
            JsonObject agent = filteredAgents.get(idx);
            int ey = LIST_TOP + i * ENTRY_H;

            if (idx == selectedIndex) g.fill(2, ey, leftEnd - 2, ey + ENTRY_H - 2, 0x60448AFF);
            boolean hovered = mouseX >= 2 && mouseX < leftEnd - 2 && mouseY >= ey && mouseY < ey + ENTRY_H - 2;
            if (hovered && idx != selectedIndex) g.fill(2, ey, leftEnd - 2, ey + ENTRY_H - 2, 0x20FFFFFF);

            String name = agent.get("name").getAsString();
            boolean spawned = agent.has("spawned") && agent.get("spawned").getAsBoolean();
            g.fill(6, ey + 6, 10, ey + 10, spawned ? 0xFF55FF55 : 0xFF808080);
            g.drawString(font, name, 14, ey + 4, 0xFFFFFF);

            if (agent.has("role") && !agent.get("role").getAsString().isEmpty()) {
                String role = agent.get("role").getAsString();
                int mw = LEFT_W - 18;
                if (font.width(role) > mw) {
                    while (font.width(role + "..") > mw && role.length() > 1) role = role.substring(0, role.length() - 1);
                    role += "..";
                }
                g.drawString(font, role, 14, ey + 13, 0x505050);
            }
        }

        if (filteredAgents.size() > visibleEntries && visibleEntries > 0) {
            int barH = Math.max(8, visibleEntries * (listBottom - LIST_TOP) / filteredAgents.size());
            int barY = LIST_TOP + (listScroll * (listBottom - LIST_TOP - barH)) / Math.max(1, maxScroll);
            g.fill(leftEnd - 3, barY, leftEnd - 1, barY + barH, 0x60FFFFFF);
        }

        // Separator
        g.fill(leftEnd, 0, leftEnd + 1, height, 0x30FFFFFF);

        // === Middle column: form fields ===
        if (selectedAgentName != null || createMode) {
            // Match vertical centering from init()
            int formH = 28 + 12 + 100 + 14 + 120;
            int formTop = Math.max(20, (height - 30 - formH) / 2);
            int y = formTop;

            // Name label + status badge
            g.drawString(font, I18n.get("gui.agent.management.name"), midX, y, 0x999999);
            if (!createMode) {
                String status = I18n.get(selectedSpawned ? "gui.agent.management.spawned" : "gui.agent.management.offline");
                int sc = selectedSpawned ? 0xFF55FF55 : 0xFF666666;
                g.drawString(font, status, midX + midW - font.width(status), y, sc);
            }
            y += 40;

            // Role label
            g.drawString(font, I18n.get("gui.agent.management.role"), midX, y, 0x999999);
            y += 100 + 22;

            // Personality label
            g.drawString(font, I18n.get("gui.agent.management.personality"), midX, y, 0x999999);

            // === Right column: Tools ===
            int ty = formTop;
            String toolLabel = availableTools.isEmpty() ? I18n.get("gui.agent.management.tools")
                : selectedTools.size() >= availableTools.size()
                    ? I18n.get("gui.agent.management.tools_all")
                    : I18n.get("gui.agent.management.tools_count", selectedTools.size(), availableTools.size());
            g.drawString(font, toolLabel, toolX, ty, 0xFFAA00);
            ty += 14;

            toolListY = ty;
            int toolsBottom2 = height - 34;
            int toolVis = Math.max(1, (toolsBottom2 - ty) / 13);
            int toolMax = Math.max(0, availableTools.size() - toolVis);
            toolScroll = Math.min(toolScroll, toolMax);

            if (availableTools.isEmpty()) {
                g.drawString(font, I18n.get("gui.agent.management.no_tools"), toolX + 4, ty, 0x606060);
            } else {
                String hoveredTool = null;
                int hoveredToolY = 0;

                for (int i = 0; i < toolVis && i + toolScroll < availableTools.size(); i++) {
                    String tool = availableTools.get(i + toolScroll);
                    boolean checked = selectedTools.contains(tool);
                    int cy = ty + i * 13;

                    // Checkbox
                    g.fill(toolX, cy, toolX + 10, cy + 10, 0xFF222222);
                    g.fill(toolX, cy, toolX + 10, cy + 1, 0xFF555555);
                    g.fill(toolX, cy + 9, toolX + 10, cy + 10, 0xFF555555);
                    g.fill(toolX, cy, toolX + 1, cy + 10, 0xFF555555);
                    g.fill(toolX + 9, cy, toolX + 10, cy + 10, 0xFF555555);
                    if (checked) g.fill(toolX + 2, cy + 2, toolX + 8, cy + 8, 0xFF55FF55);

                    // Display translated name, fallback to raw tool name
                    String toolKey = "tool.agent." + tool;
                    String displayName = I18n.exists(toolKey) ? I18n.get(toolKey) : tool;
                    g.drawString(font, displayName, toolX + 14, cy + 1, checked ? 0xFFFFFF : 0x888888);

                    int re = Math.min(toolX + 14 + font.width(displayName) + 4, width - 6);
                    if (mouseX >= toolX && mouseX < re && mouseY >= cy && mouseY < cy + 12) {
                        g.fill(toolX - 1, cy - 1, re, cy + 11, 0x14FFFFFF);
                        hoveredTool = tool;
                        hoveredToolY = cy;
                    }
                }

                if (availableTools.size() > toolVis) {
                    int barH = Math.max(6, toolVis * (toolsBottom2 - ty) / availableTools.size());
                    int barY2 = ty + (toolScroll * (toolsBottom2 - ty - barH)) / Math.max(1, toolMax);
                    g.fill(width - 4, barY2, width - 2, barY2 + barH, 0x60FFFFFF);
                }

                // Deferred tooltip rendering (after super.render)
                pendingTooltipTool = hoveredTool;
                pendingTooltipX = mouseX;
                pendingTooltipY = mouseY;
            }
        } else {
            int cx = (midX + width) / 2;
            g.drawCenteredString(font, I18n.get("gui.agent.management.select_or_create"), cx, height / 2, 0x808080);
        }

        super.render(g, mouseX, mouseY, partialTick);

        // Render tool tooltip on top of everything
        if (pendingTooltipTool != null) {
            String descKey = "tool.agent." + pendingTooltipTool + ".desc";
            String nameKey = "tool.agent." + pendingTooltipTool;
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(I18n.exists(nameKey) ? I18n.get(nameKey) : pendingTooltipTool)
                .withStyle(s -> s.withColor(0xFFAA00)));
            if (I18n.exists(descKey)) {
                lines.add(Component.literal(I18n.get(descKey))
                    .withStyle(s -> s.withColor(0xAAAAAA)));
            }
            lines.add(Component.literal(pendingTooltipTool)
                .withStyle(s -> s.withColor(0x666666).withItalic(true)));
            g.renderTooltip(font, lines, java.util.Optional.empty(), pendingTooltipX, pendingTooltipY);
            pendingTooltipTool = null;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int leftEnd = LEFT_W;
            int listBottom = height - 30;
            int visibleEntries = (listBottom - LIST_TOP) / ENTRY_H;

            // Left panel: agent selection
            if (mouseX >= 2 && mouseX < leftEnd - 2) {
                for (int i = 0; i < visibleEntries && i + listScroll < filteredAgents.size(); i++) {
                    int ey = LIST_TOP + i * ENTRY_H;
                    if (mouseY >= ey && mouseY < ey + ENTRY_H - 2) {
                        selectAgent(i + listScroll);
                        return true;
                    }
                }
            }

            // Right column: tool checkbox click
            if ((selectedAgentName != null || createMode) && mouseX >= toolX) {
                int ty = toolListY;
                int toolsBottom2 = height - 34;
                int toolVis = Math.max(1, (toolsBottom2 - ty) / 13);

                for (int i = 0; i < toolVis && i + toolScroll < availableTools.size(); i++) {
                    int cy = ty + i * 13;
                    if (mouseY >= cy && mouseY < cy + 12 && mouseX >= toolX && mouseX < width - 6) {
                        String tool = availableTools.get(i + toolScroll);
                        if (selectedTools.contains(tool)) selectedTools.remove(tool);
                        else selectedTools.add(tool);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < LEFT_W) {
            listScroll -= (int) delta;
            listScroll = Math.max(0, listScroll);
        } else if (mouseX >= toolX) {
            toolScroll -= (int) delta;
            toolScroll = Math.max(0, toolScroll);
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
