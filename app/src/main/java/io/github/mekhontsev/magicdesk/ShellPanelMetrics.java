package io.github.mekhontsev.magicdesk;

/** One density-independent source for panel thickness and automatic content dimensions. */
record ShellPanelMetrics(int thickness, int padding, int itemExtent, int iconSize, float contentScale) {
    static ShellPanelMetrics resolve(ShellAppearance.PanelStyle style, boolean compact) {
        int padding = compact ? style.paddingDp() / 2 : style.paddingDp();
        int base = compact ? 44 : 48;
        int thickness = style.thicknessDp() == 0 ? base + 2 * padding : style.thicknessDp();
        padding = Math.min(padding, Math.max(0, (thickness - 32) / 2));
        int content = thickness - 2 * padding;
        return new ShellPanelMetrics(thickness, padding, content, Math.round(content * .7f), content / (float) base);
    }
    int iconInset() { return (itemExtent - iconSize) / 2; }
    int scaled(int value) { return Math.max(1, Math.round(value * contentScale)); }
}
