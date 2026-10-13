package io.github.mekhontsev.magicdesk;

import java.util.HashMap;
import java.util.Map;

/** Pair ownership survives setting changes until release; repeat never switches twice. */
final class PaperInputShortcutState {
    record Result(boolean consumed, DesktopWindowAction command) {}
    private final Map<Integer, PaperInputAction> held = new HashMap<>();

    Result accept(int key, boolean down, int repeat, boolean enabled, PaperInputAction mapping) {
        if (down) {
            if (held.containsKey(key)) return new Result(true, null);
            if (!enabled || repeat != 0 || mapping == PaperInputAction.SYSTEM) return new Result(false, null);
            held.put(key, mapping);
            return new Result(true, null);
        }
        PaperInputAction action = held.remove(key);
        return new Result(action != null, action != null && enabled ? action.command : null);
    }
}
