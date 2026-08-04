package io.github.mekhontsev.magicdesk;

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
        FULLSCREEN,
        RESTORE,
        SNAP_LEFT,
        SNAP_RIGHT,
        SHOW_DESKTOP,
        SCREENSHOT,
        SHORTCUT_HELP
    }

    private boolean mCtrlDown;
    private boolean mAltDown;
    private boolean mShiftDown;
    private boolean mMetaDown;
    private boolean mAltTabActive;

    synchronized Action accept(final String line, final boolean fullShortcutMode) {
        if (line == null) {
            return Action.NONE;
        }
        if (!fullShortcutMode && line.startsWith("MAGICDESK_")) {
            return Action.NONE;
        }
        if (line.startsWith("MAGICDESK_ALT_TAB_ADVANCE ")) {
            mAltTabActive = true;
            return line.endsWith("reverse")
                    ? Action.ALT_TAB_REVERSE : Action.ALT_TAB_FORWARD;
        }
        if ("MAGICDESK_ALT_TAB_COMMIT".equals(line)) {
            return finishAltTab();
        }
        if (line.indexOf(" EV_KEY ") < 0) {
            return Action.NONE;
        }

        final String keyName = parseKeyName(line);
        final int keyAction = parseKeyAction(line);
        if (keyName == null || keyAction < 0 || keyAction == 2) {
            return Action.NONE;
        }
        if (isMetaKey(keyName)) {
            mMetaDown = keyAction == 1;
            return Action.NONE;
        }
        if (isCtrlKey(keyName)) {
            mCtrlDown = keyAction == 1;
            return Action.NONE;
        }
        if (isAltKey(keyName)) {
            mAltDown = keyAction == 1;
            return keyAction == 0 ? finishAltTab() : Action.NONE;
        }
        if (isShiftKey(keyName)) {
            mShiftDown = keyAction == 1;
            return Action.NONE;
        }
        if (keyAction != 1) {
            return Action.NONE;
        }
        if ("KEY_SPACE".equals(keyName) && ctrlOnly()) {
            return Action.TOGGLE_LAYOUT;
        }
        if ("KEY_ESC".equals(keyName) && noModifiers()) {
            return Action.DISMISS;
        }
        if ("KEY_D".equals(keyName) && metaOnly()) {
            return Action.SHOW_DESKTOP;
        }
        if (!fullShortcutMode) {
            return Action.NONE;
        }
        if ("KEY_F4".equals(keyName) && altOnly()) {
            return Action.CLOSE;
        }
        if (!metaOnly()) {
            return Action.NONE;
        }
        switch (keyName) {
            case "KEY_BACKSPACE":
                return Action.BACK;
            case "KEY_L":
                return Action.LOCK;
            case "KEY_N":
                return Action.NOTIFICATIONS;
            case "KEY_UP":
                return Action.FULLSCREEN;
            case "KEY_DOWN":
                return Action.RESTORE;
            case "KEY_LEFT":
                return Action.SNAP_LEFT;
            case "KEY_RIGHT":
                return Action.SNAP_RIGHT;
            case "KEY_SYSRQ":
            case "KEY_PRINT":
            case "KEY_PRINTSCREEN":
                return Action.SCREENSHOT;
            case "KEY_SLASH":
                return Action.SHORTCUT_HELP;
            default:
                return Action.NONE;
        }
    }

    synchronized boolean reset() {
        final boolean cancelAltTab = mAltTabActive;
        mCtrlDown = false;
        mAltDown = false;
        mShiftDown = false;
        mMetaDown = false;
        mAltTabActive = false;
        return cancelAltTab;
    }

    private Action finishAltTab() {
        if (!mAltTabActive) {
            return Action.NONE;
        }
        mAltTabActive = false;
        return Action.ALT_TAB_COMMIT;
    }

    private boolean ctrlOnly() {
        return mCtrlDown && !mAltDown && !mShiftDown && !mMetaDown;
    }

    private boolean altOnly() {
        return mAltDown && !mCtrlDown && !mShiftDown && !mMetaDown;
    }

    private boolean metaOnly() {
        return mMetaDown && !mCtrlDown && !mAltDown && !mShiftDown;
    }

    private boolean noModifiers() {
        return !mCtrlDown && !mAltDown && !mShiftDown && !mMetaDown;
    }

    static String parseKeyName(final String line) {
        final String[] parts = line.trim().split("\\s+");
        for (final String part : parts) {
            if (part.startsWith("KEY_")) {
                return part;
            }
        }
        return null;
    }

    static int parseKeyAction(final String line) {
        if (line.endsWith(" DOWN") || line.indexOf(" DOWN") >= 0) {
            return 1;
        }
        if (line.endsWith(" UP") || line.indexOf(" UP") >= 0) {
            return 0;
        }
        if (line.endsWith(" REPEAT") || line.indexOf(" REPEAT") >= 0) {
            return 2;
        }
        return -1;
    }

    private static boolean isCtrlKey(final String key) {
        return "KEY_LEFTCTRL".equals(key) || "KEY_RIGHTCTRL".equals(key);
    }

    private static boolean isAltKey(final String key) {
        return "KEY_LEFTALT".equals(key) || "KEY_RIGHTALT".equals(key);
    }

    private static boolean isShiftKey(final String key) {
        return "KEY_LEFTSHIFT".equals(key) || "KEY_RIGHTSHIFT".equals(key);
    }

    private static boolean isMetaKey(final String key) {
        return "KEY_LEFTMETA".equals(key) || "KEY_RIGHTMETA".equals(key);
    }
}
