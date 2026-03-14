package com.pyosechang.agent.client;

/**
 * Shared layout constants for all agent mod GUI screens.
 * Label at y, field at y + LABEL_GAP, next row at y + ROW_H.
 */
public final class GuiLayout {
    public static final int LABEL_GAP = 12;   // label → field vertical gap
    public static final int ROW_H = 36;       // one label+field row height
    public static final int FIELD_H = 16;     // standard field/button height
    public static final int TITLE_BAR_H = 14; // top title bar height
    public static final int BOTTOM_BTN_H = 20; // bottom action button height
    public static final int BOTTOM_MARGIN = 28; // bottom buttons distance from screen edge

    private GuiLayout() {}
}
