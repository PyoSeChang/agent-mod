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
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent management screen with two pages: Management and Monitor.
 * Layout: Tab bar at top, shared agent list on the left, right panel switches by page.
 * Shared left panel: Agent list (110px) — same selection state on both pages.
 * Management right panel: [Form Fields | Tools]
 * Monitor right panel: [Conversation Log + Command Input + Status Bar]
 */
public class AgentManagementScreen extends Screen {

    // --- Page enum ---
    private enum Page { MANAGEMENT, MONITOR, CONFIG }
    private Page currentPage = Page.MONITOR;

    // --- Shared constants ---
    private static final int LEFT_W = 110;
    private static final int ENTRY_H = 22;
    private static final int LIST_TOP = 46;
    private static final int TAB_BAR_H = 14;

    // --- Monitor page constants ---
    private static final int MON_LINE_H = 11;
    private static final int MON_STATUS_GREEN = 0xFF55FF55;
    private static final int MON_STATUS_BLUE = 0xFF5555FF;
    private static final int MON_DIM_GRAY = 0xFF6C6C6C;
    private static final int MON_BG_DARK = 0xFF0C0C0C;

    // Shared agent list state (used on both pages)
    private List<JsonObject> agents = new ArrayList<>();
    private List<JsonObject> filteredAgents = new ArrayList<>();
    private int selectedIndex = -1;
    private int listScroll = 0;
    private EditBox searchBox;

    // Form fields (middle column, Management page)
    private EditBox nameBox;
    private MultiLineEditBox roleBox;
    private MultiLineEditBox personalityBox;

    // Tools (right column, Management page)
    private List<String> availableTools = new ArrayList<>();
    private Set<String> selectedTools = new HashSet<>();
    private int toolScroll = 0;

    // Buttons (Management page)
    private Button createBtn, deleteBtn, spawnBtn, despawnBtn, saveBtn;

    // State (Management page)
    private String selectedAgentName = null;
    private boolean selectedSpawned = false;
    private boolean createMode = false;

    // Layout computed values (Management page)
    private int midX, midW, toolX, toolW, toolListY;

    // Tooltip state (deferred rendering, Management page)
    private String pendingTooltipTool = null;
    private int pendingTooltipX, pendingTooltipY;

    // --- Monitor page state ---
    private MonitorState monitorState;
    private EditBox commandInput;

    // --- Config page state ---
    private int configSelectedGamemode = 0; // 0=SURVIVAL, 1=CREATIVE, 2=HARDCORE
    private static final String[] GAMEMODE_NAMES = {"SURVIVAL", "CREATIVE", "HARDCORE"};
    private static final int[] GAMEMODE_COLORS = {0xFF55FF55, 0xFFFFAA00, 0xFFFF5555};
    private boolean configHasBed = false;
    private int configBedX, configBedY, configBedZ;
    private String configBedDimension = "minecraft:overworld";
    private Button configSetBedBtn, configResetBedBtn, configApplyBtn;
    private String configStatus = "";

    public AgentManagementScreen() {
        super(Component.translatable("gui.agent.management.title"));
    }

    @Override
    protected void init() {
        // Shared: agent list search box (always present on both pages)
        searchBox = new EditBox(font, 4, TAB_BAR_H + 16, LEFT_W - 8, 14, Component.translatable("gui.agent.management.search"));
        searchBox.setHint(Component.translatable("gui.agent.management.search_hint"));
        searchBox.setResponder(text -> filterAgents());
        addRenderableWidget(searchBox);

        // Always refresh agent list on both pages
        refreshAgentList();

        if (currentPage == Page.MANAGEMENT) {
            initManagement();
        } else if (currentPage == Page.MONITOR) {
            initMonitor();
        } else if (currentPage == Page.CONFIG) {
            initConfig();
        }
    }

    private void initManagement() {
        // Layout columns -- center form+tools in right area
        int leftEnd = LEFT_W;
        int rightArea = width - leftEnd;
        int gap = 16;
        midW = Math.min((rightArea - gap * 3) / 2, 280);
        toolW = Math.min((rightArea - gap * 3) / 2, 220);
        int totalContentW = midW + gap + toolW;
        midX = leftEnd + (rightArea - totalContentW) / 2;
        toolX = midX + midW + gap;

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
        loadAvailableTools();
    }

    private void initMonitor() {
        // Lazily create MonitorState on first Monitor page switch
        if (monitorState == null) {
            monitorState = new MonitorState();
            monitorState.connect();
            monitorState.loadSessionInfo();
            monitorState.loadHistory();
        }

        // Command input at bottom of right panel
        int inputY = height - 34;
        int inputX = LEFT_W + 6;
        int inputW = width - inputX - 6;
        commandInput = new EditBox(font, inputX, inputY, inputW, 14, Component.literal("command"));
        commandInput.setHint(Component.literal("Type a command...").withStyle(Style.EMPTY.withColor(0xFF555555)));
        commandInput.setMaxLength(500);
        addRenderableWidget(commandInput);
    }

    private void initConfig() {
        int btnY = height - 26;
        int rightStart = LEFT_W + 20;

        configSetBedBtn = addRenderableWidget(Button.builder(
            Component.translatable("gui.agent.config.set_bed"), btn -> doSetBed())
            .bounds(rightStart, btnY - 60, 100, 20).build());

        configResetBedBtn = addRenderableWidget(Button.builder(
            Component.translatable("gui.agent.config.reset_bed"), btn -> doResetBed())
            .bounds(rightStart + 106, btnY - 60, 60, 20).build());

        configApplyBtn = addRenderableWidget(Button.builder(
            Component.translatable("gui.agent.config.apply"), btn -> doApplyConfig())
            .bounds(width / 2 - 40, btnY, 80, 20).build());

        updateConfigButtons();
        if (selectedAgentName != null) loadConfig();
    }

    private void updateConfigButtons() {
        boolean hasAgent = selectedAgentName != null;
        boolean isManager = "manager".equals(selectedAgentName);
        if (configSetBedBtn != null) configSetBedBtn.active = hasAgent && !isManager;
        if (configResetBedBtn != null) configResetBedBtn.active = hasAgent && !isManager && configHasBed;
        if (configApplyBtn != null) configApplyBtn.active = hasAgent && !isManager;
    }

    private void loadConfig() {
        if (selectedAgentName == null || "manager".equals(selectedAgentName)) return;
        BridgeClient.get("/agent/" + selectedAgentName + "/config").thenAccept(result -> {
            Minecraft.getInstance().tell(() -> {
                if (result.has("config")) {
                    JsonObject cfg = result.getAsJsonObject("config");
                    String gm = cfg.has("gamemode") ? cfg.get("gamemode").getAsString() : "SURVIVAL";
                    configSelectedGamemode = switch (gm) {
                        case "CREATIVE" -> 1;
                        case "HARDCORE" -> 2;
                        default -> 0;
                    };
                    if (cfg.has("bed") && !cfg.get("bed").isJsonNull()) {
                        JsonObject bed = cfg.getAsJsonObject("bed");
                        configHasBed = true;
                        configBedX = bed.get("x").getAsInt();
                        configBedY = bed.get("y").getAsInt();
                        configBedZ = bed.get("z").getAsInt();
                        configBedDimension = bed.has("dimension") ? bed.get("dimension").getAsString() : "minecraft:overworld";
                    } else {
                        configHasBed = false;
                    }
                }
                configStatus = "";
                updateConfigButtons();
            });
        });
    }

    private void doSetBed() {
        if (minecraft.player == null) return;
        configHasBed = true;
        configBedX = minecraft.player.blockPosition().getX();
        configBedY = minecraft.player.blockPosition().getY();
        configBedZ = minecraft.player.blockPosition().getZ();
        configBedDimension = minecraft.player.level().dimension().location().toString();
        updateConfigButtons();
    }

    private void doResetBed() {
        configHasBed = false;
        updateConfigButtons();
    }

    private void doApplyConfig() {
        if (selectedAgentName == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("gamemode", GAMEMODE_NAMES[configSelectedGamemode]);
        if (minecraft.player != null) {
            body.addProperty("player", minecraft.player.getName().getString());
        }
        if (configHasBed) {
            JsonObject bed = new JsonObject();
            bed.addProperty("x", configBedX);
            bed.addProperty("y", configBedY);
            bed.addProperty("z", configBedZ);
            bed.addProperty("dimension", configBedDimension);
            body.add("bed", bed);
        } else {
            body.add("bed", com.google.gson.JsonNull.INSTANCE);
        }
        BridgeClient.post("/agent/" + selectedAgentName + "/config", body).thenAccept(result -> {
            Minecraft.getInstance().tell(() -> {
                if (result.has("ok") && result.get("ok").getAsBoolean()) {
                    configStatus = I18n.get("gui.agent.config.saved");
                } else {
                    configStatus = result.has("error") ? result.get("error").getAsString() : "Error";
                }
            });
        });
    }

    // --- Page switching ---

    private void switchPage(Page page) {
        if (currentPage == page) return;
        currentPage = page;
        rebuildWidgets();
    }

    // --- Management page methods ---

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
        boolean isManager = "manager".equals(selectedAgentName);
        deleteBtn.active = hasAgent && !selectedSpawned && !isManager;
        spawnBtn.active = hasAgent && !selectedSpawned && !isManager;
        despawnBtn.active = hasAgent && selectedSpawned && !isManager;
        saveBtn.active = hasAgent && !isManager;
        createBtn.setMessage(Component.translatable(createMode ? "gui.agent.management.confirm" : "gui.agent.management.create"));
    }

    private void refreshAgentList() {
        BridgeClient.get("/agents/list").thenAccept(result -> {
            if (result.has("agents")) {
                List<JsonObject> list = new ArrayList<>();
                // Always include manager as first entry
                boolean hasManager = false;
                for (JsonElement el : result.getAsJsonArray("agents")) {
                    JsonObject a = el.getAsJsonObject();
                    if ("manager".equals(a.get("name").getAsString())) hasManager = true;
                    list.add(a);
                }
                if (!hasManager) {
                    JsonObject mgr = new JsonObject();
                    mgr.addProperty("name", "manager");
                    mgr.addProperty("spawned", false);
                    list.add(0, mgr);
                }
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

        // Sync monitor state if available
        syncMonitorSelection();

        // Load config when on Config page
        if (currentPage == Page.CONFIG) {
            loadConfig();
            updateConfigButtons();
        }

        // Only load persona details if on Management page (form fields exist)
        if (currentPage == Page.MANAGEMENT) {
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
    }

    /**
     * Sync the shared agent list selection to MonitorState.
     * Finds the selected agent name in monitorState's sorted names and selects it.
     */
    private void syncMonitorSelection() {
        if (monitorState == null || selectedAgentName == null) return;
        List<String> monNames = monitorState.getSortedNames();
        for (int i = 0; i < monNames.size(); i++) {
            if (selectedAgentName.equals(monNames.get(i))) {
                monitorState.selectAgent(i);
                return;
            }
        }
    }

    private void clearSelection() {
        selectedIndex = -1;
        selectedAgentName = null;
        selectedSpawned = false;
        createMode = false;
        if (currentPage == Page.MANAGEMENT) {
            nameBox.setValue("");
            roleBox.setValue("");
            personalityBox.setValue("");
            selectedTools.clear();
            updateVisibility();
            updateButtons();
        }
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
        renderTabBar(g, mouseX, mouseY);

        // Shared left panel: agent list (always rendered on both pages)
        renderSharedAgentList(g, mouseX, mouseY);

        if (currentPage == Page.MANAGEMENT) {
            renderManagementRightPanel(g, mouseX, mouseY, partialTick);
        } else if (currentPage == Page.MONITOR) {
            renderMonitorRightPanel(g, mouseX, mouseY, partialTick);
        } else if (currentPage == Page.CONFIG) {
            renderConfigRightPanel(g, mouseX, mouseY, partialTick);
        }
    }

    private void renderTabBar(GuiGraphics g, int mouseX, int mouseY) {
        // Tab bar background
        g.fill(0, 0, width, TAB_BAR_H, 0xFF1A1A1A);

        // Tab definitions
        String mgmtLabel = "[Management]";
        String monLabel = "[Monitor]";
        String cfgLabel = "[Config]";
        int mgmtW = font.width(mgmtLabel);
        int monW = font.width(monLabel);
        int cfgW = font.width(cfgLabel);

        int tabGap = 8;
        int totalW = mgmtW + tabGap + monW + tabGap + cfgW;
        int startX = width / 2 - totalW / 2;

        // Management tab
        int mgmtColor = currentPage == Page.MANAGEMENT ? 0xFFFFFFFF : 0xFF888888;
        boolean mgmtHovered = mouseX >= startX && mouseX < startX + mgmtW && mouseY >= 0 && mouseY < TAB_BAR_H;
        if (mgmtHovered && currentPage != Page.MANAGEMENT) mgmtColor = 0xFFBBBBBB;
        g.drawString(font, mgmtLabel, startX, 3, mgmtColor);
        if (currentPage == Page.MANAGEMENT) {
            g.fill(startX, TAB_BAR_H - 1, startX + mgmtW, TAB_BAR_H, 0xFFFFFFFF);
        }

        // Monitor tab
        int monX = startX + mgmtW + tabGap;
        int monColor = currentPage == Page.MONITOR ? 0xFFFFFFFF : 0xFF888888;
        boolean monHovered = mouseX >= monX && mouseX < monX + monW && mouseY >= 0 && mouseY < TAB_BAR_H;
        if (monHovered && currentPage != Page.MONITOR) monColor = 0xFFBBBBBB;
        g.drawString(font, monLabel, monX, 3, monColor);
        if (currentPage == Page.MONITOR) {
            g.fill(monX, TAB_BAR_H - 1, monX + monW, TAB_BAR_H, 0xFFFFFFFF);
        }

        // Config tab
        int cfgX = monX + monW + tabGap;
        int cfgColor = currentPage == Page.CONFIG ? 0xFFFFFFFF : 0xFF888888;
        boolean cfgHovered = mouseX >= cfgX && mouseX < cfgX + cfgW && mouseY >= 0 && mouseY < TAB_BAR_H;
        if (cfgHovered && currentPage != Page.CONFIG) cfgColor = 0xFFBBBBBB;
        g.drawString(font, cfgLabel, cfgX, 3, cfgColor);
        if (currentPage == Page.CONFIG) {
            g.fill(cfgX, TAB_BAR_H - 1, cfgX + cfgW, TAB_BAR_H, 0xFFFFFFFF);
        }
    }

    /**
     * Shared left panel: agent list rendered on both Management and Monitor pages.
     * Uses the shared filteredAgents/selectedIndex/listScroll state.
     */
    private void renderSharedAgentList(GuiGraphics g, int mouseX, int mouseY) {
        int leftEnd = LEFT_W;

        // Background
        g.fill(0, TAB_BAR_H, leftEnd, height, 0xFF1A1A1A);
        g.drawString(font, I18n.get("gui.agent.management.agents_count", filteredAgents.size()), 6, TAB_BAR_H + 4, 0xCCCCCC);
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
    }

    private void renderManagementRightPanel(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
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

    private void renderMonitorRightPanel(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (monitorState == null) {
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        Style monFont = Style.EMPTY.withFont(EventFormatter.MONITOR_FONT);

        // === Right panel: Conversation log ===
        int logLeft = LEFT_W + 1;
        int logTop = TAB_BAR_H;
        int logBottom = height - 40;
        int logRight = width;

        // Dark background
        g.fill(logLeft, logTop, logRight, logBottom, MON_BG_DARK);

        // Render conversation messages
        List<MonitorMessage> messages = monitorState.getVisibleMessages();
        int scrollOffset = monitorState.getScrollOffset();

        // Calculate total lines
        int totalLines = 0;
        for (MonitorMessage msg : messages) {
            totalLines += msg.lines().size();
        }

        // Visible lines count
        int visibleLines = (logBottom - logTop) / MON_LINE_H;

        // Render from bottom up with scroll offset
        g.enableScissor(logLeft, logTop, logRight, logBottom);
        int renderY = logBottom - MON_LINE_H;
        int linesSkipped = 0;

        // Walk messages backward
        for (int m = messages.size() - 1; m >= 0 && renderY >= logTop - MON_LINE_H; m--) {
            MonitorMessage msg = messages.get(m);
            List<Component> lines = msg.lines();

            // Walk lines backward within message
            for (int l = lines.size() - 1; l >= 0 && renderY >= logTop - MON_LINE_H; l--) {
                if (linesSkipped < scrollOffset) {
                    linesSkipped++;
                    continue;
                }
                g.drawString(font, lines.get(l), logLeft + 4, renderY, 0xFFFFFF, false);
                renderY -= MON_LINE_H;
            }
        }
        g.disableScissor();

        // Log scrollbar
        if (totalLines > visibleLines && visibleLines > 0) {
            int maxScrollVal = Math.max(totalLines - visibleLines, 1);
            int barH = Math.max(6, visibleLines * (logBottom - logTop) / totalLines);
            int barTrack = logBottom - logTop - barH;
            int barY = logTop + barTrack - (scrollOffset * barTrack) / maxScrollVal;
            g.fill(logRight - 3, barY, logRight - 1, barY + barH, 0x40FFFFFF);
        }

        // === Bottom: Status bar ===
        int statusLineY = height - 40;
        g.fill(logLeft, statusLineY, logRight, statusLineY + 1, 0x30FFFFFF);

        // Status bar text
        int statusTextY = height - 16;
        String sseStatus = monitorState.getSSEStatus();
        String verboseHint = monitorState.isVerbose() ? "Verbose ON" : "Verbose OFF";
        String statusText = "SSE: " + sseStatus + "  |  Ctrl+O: " + verboseHint + "  |  \u2191\u2193: select agent";
        g.drawString(font, Component.literal(statusText).withStyle(monFont.withColor(MON_DIM_GRAY)),
            logLeft + 4, statusTextY, MON_DIM_GRAY, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderConfigRightPanel(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int rightStart = LEFT_W + 20;
        int rightEnd = width - 20;

        if (selectedAgentName == null || "manager".equals(selectedAgentName)) {
            int cx = (LEFT_W + width) / 2;
            g.drawCenteredString(font, I18n.get("gui.agent.config.select_agent"), cx, height / 2, 0x808080);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        int y = TAB_BAR_H + 20;

        // Agent name + status
        g.drawString(font, "Agent: " + selectedAgentName, rightStart, y, 0xFFFFFF);
        String status = I18n.get(selectedSpawned ? "gui.agent.management.spawned" : "gui.agent.management.offline");
        int sc = selectedSpawned ? 0xFF55FF55 : 0xFF666666;
        g.drawString(font, status, rightEnd - font.width(status), y, sc);
        y += 20;

        // --- Gamemode section ---
        g.fill(rightStart, y, rightEnd, y + 1, 0x30FFFFFF);
        y += 6;
        g.drawString(font, I18n.get("gui.agent.config.gamemode"), rightStart, y, 0x999999);
        y += 14;

        // Radio buttons for gamemode
        for (int i = 0; i < 3; i++) {
            int rx = rightStart + i * 90;
            int ry = y;
            boolean selected = configSelectedGamemode == i;
            boolean hovered = mouseX >= rx && mouseX < rx + 85 && mouseY >= ry && mouseY < ry + 14;

            // Radio circle
            int circleColor = selected ? GAMEMODE_COLORS[i] : 0xFF555555;
            g.fill(rx, ry + 2, rx + 8, ry + 10, 0xFF222222);
            if (selected) g.fill(rx + 2, ry + 4, rx + 6, ry + 8, circleColor);
            g.drawString(font, I18n.get("gui.agent.config.gamemode." + GAMEMODE_NAMES[i].toLowerCase()),
                rx + 12, ry + 2, selected ? GAMEMODE_COLORS[i] : (hovered ? 0xBBBBBB : 0x888888));
        }
        y += 20;

        // Warnings
        if (configSelectedGamemode == 1) { // CREATIVE
            g.drawString(font, I18n.get("gui.agent.config.creative_warning"), rightStart, y, 0xFFAA00);
            y += 12;
        } else if (configSelectedGamemode == 2) { // HARDCORE
            g.drawString(font, I18n.get("gui.agent.config.hardcore_warning"), rightStart, y, 0xFF5555);
            y += 12;
        }
        y += 10;

        // --- Bed section ---
        g.fill(rightStart, y, rightEnd, y + 1, 0x30FFFFFF);
        y += 6;
        g.drawString(font, I18n.get("gui.agent.config.bed"), rightStart, y, 0x999999);
        y += 14;

        if (configHasBed) {
            String bedText = String.format("(%d, %d, %d) %s", configBedX, configBedY, configBedZ,
                configBedDimension.replace("minecraft:", ""));
            g.drawString(font, bedText, rightStart, y, 0xFFFFFF);
        } else {
            g.drawString(font, I18n.get("gui.agent.config.bed_none"), rightStart, y, 0x666666);
        }

        // Status message
        if (!configStatus.isEmpty()) {
            g.drawCenteredString(font, configStatus, (LEFT_W + width) / 2, height - 46, 0x55FF55);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private boolean mouseClickedConfigRight(double mouseX, double mouseY, int button) {
        if (selectedAgentName == null || "manager".equals(selectedAgentName)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        int rightStart = LEFT_W + 20;
        int y = TAB_BAR_H + 20 + 20 + 6 + 14; // matches renderConfigRightPanel gamemode radio Y

        // Gamemode radio click
        for (int i = 0; i < 3; i++) {
            int rx = rightStart + i * 90;
            if (mouseX >= rx && mouseX < rx + 85 && mouseY >= y && mouseY < y + 14) {
                configSelectedGamemode = i;
                configStatus = "";
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    // === Input handling ===

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Tab bar click
            if (mouseY >= 0 && mouseY < TAB_BAR_H) {
                String mgmtLabel = "[Management]";
                String monLabel = "[Monitor]";
                String cfgLabel = "[Config]";
                int mgmtW = font.width(mgmtLabel);
                int monW = font.width(monLabel);
                int cfgW = font.width(cfgLabel);
                int tabGap = 8;
                int totalW = mgmtW + tabGap + monW + tabGap + cfgW;
                int startX = width / 2 - totalW / 2;

                if (mouseX >= startX && mouseX < startX + mgmtW) {
                    switchPage(Page.MANAGEMENT);
                    return true;
                }
                int monX = startX + mgmtW + tabGap;
                if (mouseX >= monX && mouseX < monX + monW) {
                    switchPage(Page.MONITOR);
                    return true;
                }
                int cfgX = monX + monW + tabGap;
                if (mouseX >= cfgX && mouseX < cfgX + cfgW) {
                    switchPage(Page.CONFIG);
                    return true;
                }
            }

            // Shared left panel: agent list click (works on both pages)
            if (mouseX >= 2 && mouseX < LEFT_W - 2 && mouseY >= LIST_TOP) {
                int listBottom = height - 30;
                int visibleEntries = (listBottom - LIST_TOP) / ENTRY_H;
                for (int i = 0; i < visibleEntries && i + listScroll < filteredAgents.size(); i++) {
                    int ey = LIST_TOP + i * ENTRY_H;
                    if (mouseY >= ey && mouseY < ey + ENTRY_H - 2) {
                        selectAgent(i + listScroll);
                        return true;
                    }
                }
            }

            // Right panel clicks (page-specific)
            if (currentPage == Page.MANAGEMENT) {
                return mouseClickedManagementRight(mouseX, mouseY, button);
            } else if (currentPage == Page.CONFIG) {
                return mouseClickedConfigRight(mouseX, mouseY, button);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean mouseClickedManagementRight(double mouseX, double mouseY, int button) {
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

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < LEFT_W) {
            // Shared agent list scroll (both pages)
            listScroll -= (int) delta;
            listScroll = Math.max(0, listScroll);
            return true;
        }

        if (currentPage == Page.MANAGEMENT) {
            if (mouseX >= toolX) {
                toolScroll -= (int) delta;
                toolScroll = Math.max(0, toolScroll);
            }
            return true;
        } else {
            // Monitor page: conversation log scroll
            if (monitorState == null) return true;
            int lines = 3;
            if (delta > 0) {
                monitorState.scrollUp(lines);
            } else {
                monitorState.scrollDown(lines);
            }
            return true;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Tab switching with 1/2 keys (only when no EditBox is focused)
        boolean editBoxFocused = isEditBoxFocused();
        if (!editBoxFocused) {
            if (keyCode == 49) { // '1'
                switchPage(Page.MANAGEMENT);
                return true;
            }
            if (keyCode == 50) { // '2'
                switchPage(Page.MONITOR);
                return true;
            }
            if (keyCode == 51) { // '3'
                switchPage(Page.CONFIG);
                return true;
            }
        }

        if (currentPage == Page.MONITOR) {
            return keyPressedMonitor(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean keyPressedMonitor(int keyCode, int scanCode, int modifiers) {
        if (monitorState == null) return super.keyPressed(keyCode, scanCode, modifiers);

        boolean commandFocused = commandInput != null && commandInput.isFocused();

        // Enter: send command
        if (keyCode == 257 && commandFocused) { // GLFW_KEY_ENTER
            String text = commandInput.getValue().trim();
            if (!text.isEmpty()) {
                sendMonitorCommand(text);
                commandInput.setValue("");
            }
            return true;
        }

        // Ctrl+O: toggle verbose
        if (keyCode == 79 && (modifiers & 2) != 0) { // 'O' + Ctrl
            monitorState.toggleVerbose();
            return true;
        }

        // Up/Down arrows: move agent selection (when command input not focused)
        if (!commandFocused) {
            if (keyCode == 265) { // GLFW_KEY_UP
                monitorState.moveUp();
                return true;
            }
            if (keyCode == 264) { // GLFW_KEY_DOWN
                monitorState.moveDown();
                return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean isEditBoxFocused() {
        if (searchBox != null && searchBox.isFocused()) return true;
        if (currentPage == Page.MANAGEMENT) {
            return (nameBox != null && nameBox.isFocused())
                || (roleBox != null && roleBox.isFocused())
                || (personalityBox != null && personalityBox.isFocused());
        } else if (currentPage == Page.MONITOR) {
            return commandInput != null && commandInput.isFocused();
        }
        return false; // CONFIG page has no edit boxes
    }

    private void sendMonitorCommand(String text) {
        String agent = monitorState.getSelectedAgent();
        monitorState.addUserMessage(text);

        if (text.equalsIgnoreCase("/spawn")) {
            if (minecraft.player != null) {
                JsonObject body = new JsonObject();
                body.addProperty("name", agent);
                body.addProperty("x", minecraft.player.blockPosition().getX());
                body.addProperty("y", minecraft.player.blockPosition().getY());
                body.addProperty("z", minecraft.player.blockPosition().getZ());
                BridgeClient.post("/agent/" + agent + "/spawn", body);
            }
        } else if (text.equalsIgnoreCase("/despawn")) {
            BridgeClient.post("/agent/" + agent + "/despawn", new JsonObject());
        } else if (text.equalsIgnoreCase("/stop")) {
            BridgeClient.post("/agent/" + agent + "/stop", new JsonObject());
        } else {
            // Tell agent
            JsonObject body = new JsonObject();
            body.addProperty("message", text);
            if ("manager".equals(agent)) {
                BridgeClient.post("/manager/tell", body);
            } else {
                BridgeClient.post("/agent/" + agent + "/tell", body);
            }
        }
    }

    // === Lifecycle ===

    @Override
    public void onClose() {
        if (monitorState != null) {
            monitorState.disconnect();
            monitorState = null;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
