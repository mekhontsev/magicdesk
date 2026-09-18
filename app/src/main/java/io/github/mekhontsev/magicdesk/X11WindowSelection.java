package io.github.mekhontsev.magicdesk;

import java.util.List;
import io.github.mekhontsev.magicdesk.x11.X11Session;

/** A launch host presents startup content until the first identified application window. */
final class X11WindowSelection {
    static long select(long current, boolean provisional, List<X11Session.Window> windows) {
        // An identified selection is authoritative even if it was provisional in the previous snapshot.
        for (var item : windows) if (item.id() == current && (!provisional || !item.provisional())) return current;
        if (provisional) {
            for (var item : windows) if (item.mapped() && !item.provisional()) return item.id();
        }
        for (var item : windows) if (item.id() == current) return current;
        if (current != 0 && !provisional) return -1;
        for (var item : windows) if (item.mapped()) return item.id();
        return 0;
    }

    private X11WindowSelection() { }
}
