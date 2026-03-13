package com.pyosechang.agent.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Custom dropdown widget for Minecraft 1.20.1.
 * Click to open, select an option, click away to close.
 */
public class DropdownWidget extends AbstractWidget {
    private static final int ITEM_HEIGHT = 14;
    private static final int BG_COLOR = 0xFF1A1A1A;
    private static final int BORDER_COLOR = 0xFF808080;
    private static final int HOVER_COLOR = 0xFF404040;
    private static final int SELECTED_COLOR = 0xFF333333;

    private final Font font;
    private final List<Entry> entries = new ArrayList<>();
    private int selectedIndex = 0;
    private boolean open = false;
    private Consumer<Entry> onSelect;

    public DropdownWidget(Font font, int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
        this.font = font;
    }

    public void setOnSelect(Consumer<Entry> onSelect) {
        this.onSelect = onSelect;
    }

    public void clearEntries() {
        entries.clear();
        selectedIndex = 0;
    }

    public void addEntry(String key, String displayName, int color) {
        entries.add(new Entry(key, displayName, color));
    }

    public void addEntry(String key, String displayName) {
        addEntry(key, displayName, 0xFFFFFF);
    }

    public void setSelected(int index) {
        if (index >= 0 && index < entries.size()) {
            selectedIndex = index;
        }
    }

    public void setSelectedByKey(String key) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).key.equals(key)) {
                selectedIndex = i;
                return;
            }
        }
    }

    public Entry getSelected() {
        if (entries.isEmpty()) return null;
        return entries.get(selectedIndex);
    }

    public String getSelectedKey() {
        Entry e = getSelected();
        return e != null ? e.key : "";
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
    }

    /** Total height when dropdown is expanded (for z-ordering) */
    public int getExpandedHeight() {
        return height + entries.size() * ITEM_HEIGHT + 2;
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Closed button
        int x = getX();
        int y = getY();

        // Background
        g.fill(x, y, x + width, y + height, BG_COLOR);
        // Border
        g.fill(x, y, x + width, y + 1, BORDER_COLOR);
        g.fill(x, y + height - 1, x + width, y + height, BORDER_COLOR);
        g.fill(x, y, x + 1, y + height, BORDER_COLOR);
        g.fill(x + width - 1, y, x + width, y + height, BORDER_COLOR);

        // Selected text
        if (!entries.isEmpty()) {
            Entry sel = entries.get(selectedIndex);
            String text = sel.displayName;
            if (font.width(text) > width - 16) {
                while (font.width(text + "..") > width - 16 && text.length() > 1) {
                    text = text.substring(0, text.length() - 1);
                }
                text += "..";
            }
            g.drawString(font, text, x + 4, y + (height - 8) / 2, sel.color);
        }

        // Arrow indicator
        String arrow = open ? "\u25B2" : "\u25BC";
        g.drawString(font, arrow, x + width - font.width(arrow) - 3, y + (height - 8) / 2, 0xAAAAAA);
    }

    /** Render the open dropdown list. Call AFTER all other widgets render (overlay). */
    public void renderDropdown(GuiGraphics g, int mouseX, int mouseY) {
        if (!open || entries.isEmpty()) return;

        // Push Z level so dropdown renders above everything
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);

        int x = getX();
        int y = getY() + height;
        int listH = entries.size() * ITEM_HEIGHT;

        // Outer border
        g.fill(x - 1, y - 1, x + width + 1, y + listH + 1, BORDER_COLOR);
        // Solid background
        g.fill(x, y, x + width, y + listH, BG_COLOR);

        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int iy = y + i * ITEM_HEIGHT;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= iy && mouseY < iy + ITEM_HEIGHT;

            if (i == selectedIndex) {
                g.fill(x, iy, x + width, iy + ITEM_HEIGHT, SELECTED_COLOR);
            } else if (hovered) {
                g.fill(x, iy, x + width, iy + ITEM_HEIGHT, HOVER_COLOR);
            }

            g.drawString(font, entry.displayName, x + 4, iy + 3, entry.color);

            // Separator line between items
            if (i < entries.size() - 1) {
                g.fill(x + 2, iy + ITEM_HEIGHT - 1, x + width - 2, iy + ITEM_HEIGHT, 0xFF3A3A3A);
            }
        }

        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0) return false;

        int x = getX();
        int y = getY();

        // Click on the closed button area → toggle
        if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height) {
            open = !open;
            return true;
        }

        // Click on dropdown list item
        if (open) {
            int listY = y + height;
            if (mouseX >= x && mouseX < x + width && mouseY >= listY) {
                int idx = (int) (mouseY - listY) / ITEM_HEIGHT;
                if (idx >= 0 && idx < entries.size()) {
                    selectedIndex = idx;
                    open = false;
                    if (onSelect != null) onSelect.accept(entries.get(idx));
                    return true;
                }
            }
            // Click outside → close
            open = false;
            return true;
        }

        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, getMessage());
    }

    public record Entry(String key, String displayName, int color) {}
}
