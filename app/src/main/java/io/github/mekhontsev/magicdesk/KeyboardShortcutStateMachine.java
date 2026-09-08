package io.github.mekhontsev.magicdesk;

import android.view.KeyEvent;

import java.util.HashSet;
import java.util.Set;

/** One keyboard's balanced shortcut stream, independent of transport and windows. */
final class KeyboardShortcutStateMachine {
    enum Action {
        NONE,
        ALT_TAB_FORWARD,
        ALT_TAB_REVERSE,
        ALT_TAB_COMMIT,
        TOGGLE_LAYOUT,
        DISMISS,
        CLOSE,
        BACK,
        LOCK,
        NOTIFICATIONS,
        SYSTEM,
        SETTINGS,
        FULLSCREEN,
        RESTORE,
        SNAP_LEFT,
        SNAP_RIGHT,
        SHOW_DESKTOP,
        SCREENSHOT,
        SCREEN_RECORDING,
        SHORTCUT_HELP
    }


    static final class Result {
        final boolean consumed;
        final Action action;
        Result(final boolean consumed, final Action action) {
            this.consumed = consumed;
            this.action = action;
        }
    }

    private final Set<Integer> mConsumed = new HashSet<>();
    private boolean mAltTabActive;

    Result accept(final int key, final boolean down, final int repeats,
            final boolean ctrl, final boolean alt, final boolean shift, final boolean meta) {
        if (!down) {
            final boolean consumed = mConsumed.remove(key);
            if ((key == KeyEvent.KEYCODE_ALT_LEFT || key == KeyEvent.KEYCODE_ALT_RIGHT)
                    && !alt && mAltTabActive) {
                mAltTabActive = false;
                return new Result(consumed, Action.ALT_TAB_COMMIT);
            }
            return new Result(consumed, Action.NONE);
        }
        if (mConsumed.contains(key)) {
            return new Result(true, Action.NONE);
        }
        // Suppress the system's standalone Meta action along with our Meta chords.
        if (key == KeyEvent.KEYCODE_META_LEFT || key == KeyEvent.KEYCODE_META_RIGHT) {
            mConsumed.add(key);
            return new Result(true, Action.NONE);
        }
        if (repeats != 0) {
            return new Result(false, Action.NONE);
        }
        final Action action = action(key, ctrl, alt, shift, meta);
        if (action == Action.ALT_TAB_FORWARD || action == Action.ALT_TAB_REVERSE) {
            mAltTabActive = true;
        }
        // Escape continues to the focused app, as well as dismissing our transient UI.
        final boolean consumed = action != Action.NONE && action != Action.DISMISS;
        if (consumed) {
            mConsumed.add(key);
        }
        return new Result(consumed, action);
    }

    boolean reset() {
        final boolean cancel = mAltTabActive;
        mAltTabActive = false;
        mConsumed.clear();
        return cancel;
    }

    private static Action action(final int key, final boolean ctrl, final boolean alt,
            final boolean shift, final boolean meta) {
        if (alt && !ctrl && !meta) {
            if (key == KeyEvent.KEYCODE_TAB) {
                return shift ? Action.ALT_TAB_REVERSE : Action.ALT_TAB_FORWARD;
            }
            if (!shift && key == KeyEvent.KEYCODE_F4) {
                return Action.CLOSE;
            }
        }
        if (ctrl && !alt && !shift && !meta && key == KeyEvent.KEYCODE_SPACE) {
            return Action.TOGGLE_LAYOUT;
        }
        if (!ctrl && !alt && !shift && !meta && key == KeyEvent.KEYCODE_ESCAPE) {
            return Action.DISMISS;
        }
        if (!meta || ctrl || alt) {
            return Action.NONE;
        }
        if (key == KeyEvent.KEYCODE_SYSRQ) {
            return shift ? Action.SCREEN_RECORDING : Action.SCREENSHOT;
        }
        if (shift) {
            return Action.NONE;
        }
        return switch (key) {
            case KeyEvent.KEYCODE_DEL -> Action.BACK;
            case KeyEvent.KEYCODE_L -> Action.LOCK;
            case KeyEvent.KEYCODE_N -> Action.NOTIFICATIONS;
            case KeyEvent.KEYCODE_Q -> Action.SYSTEM;
            case KeyEvent.KEYCODE_I -> Action.SETTINGS;
            case KeyEvent.KEYCODE_DPAD_UP -> Action.FULLSCREEN;
            case KeyEvent.KEYCODE_DPAD_DOWN -> Action.RESTORE;
            case KeyEvent.KEYCODE_DPAD_LEFT -> Action.SNAP_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> Action.SNAP_RIGHT;
            case KeyEvent.KEYCODE_D -> Action.SHOW_DESKTOP;
            case KeyEvent.KEYCODE_SLASH -> Action.SHORTCUT_HELP;
            default -> Action.NONE;
        };
    }
}
