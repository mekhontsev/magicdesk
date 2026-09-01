package io.github.mekhontsev.magicdesk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, typed view of committed input-window topology. */
final class FrameworkInputWindowState {
    private static final int INPUT_CONFIG_NO_INPUT_CHANNEL = 1;
    private static final int INPUT_CONFIG_NOT_VISIBLE = 2;
    private static final int INPUT_CONFIG_NOT_FOCUSABLE = 4;
    private static final int INPUT_CONFIG_CLONE = 65_536;
    static final class Window {
        final int displayId;
        final String packageName;
        final String name;
        final int ownerUid;
        final int inputConfig;

        Window(
                final int displayId,
                final String packageName,
                final String name,
                final int ownerUid,
                final int inputConfig) {
            this.displayId = displayId;
            this.packageName = packageName == null ? "" : packageName;
            this.name = name == null ? "" : name;
            this.ownerUid = ownerUid;
            this.inputConfig = inputConfig;
        }

        boolean isFocusCandidate() {
            final int excluded = INPUT_CONFIG_NO_INPUT_CHANNEL
                    | INPUT_CONFIG_NOT_VISIBLE
                    | INPUT_CONFIG_NOT_FOCUSABLE
                    | INPUT_CONFIG_CLONE;
            return displayId >= 0 && (inputConfig & excluded) == 0;
        }

    }

    static final class Snapshot {
        private final Map<Integer, Window> mFocusedWindows;
        final boolean available;

        private Snapshot(
                final Map<Integer, Window> focusedWindows,
                final boolean available) {
            mFocusedWindows = Collections.unmodifiableMap(
                    new LinkedHashMap<>(focusedWindows));
            this.available = available;
        }

        static Snapshot unavailable() {
            return new Snapshot(Collections.emptyMap(), false);
        }

        Window focusedWindow(final int displayId) {
            return mFocusedWindows.get(Integer.valueOf(displayId));
        }

    }

    private FrameworkInputWindowState() {
    }

    static Snapshot fromWindows(final List<Window> windows) {
        if (windows == null) {
            return Snapshot.unavailable();
        }
        final Map<Integer, Window> focused = new LinkedHashMap<>();
        // The observation source supplies windows in descending Z order. The
        // first visible focusable input target on each display owns focus.
        for (final Window window : windows) {
            if (window == null || !window.isFocusCandidate()
                    || focused.containsKey(Integer.valueOf(window.displayId))) {
                continue;
            }
            focused.put(Integer.valueOf(window.displayId), window);
        }
        return new Snapshot(focused, true);
    }

}
