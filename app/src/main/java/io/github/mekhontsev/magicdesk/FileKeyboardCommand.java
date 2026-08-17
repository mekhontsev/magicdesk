package io.github.mekhontsev.magicdesk;

import android.view.KeyEvent;

/** Maps Android key events to file-workspace commands shared by Desktop and Files. */
enum FileKeyboardCommand {
    NONE,
    FIND,
    NEW_WINDOW,
    FOCUS_LOCATION,
    SELECT_ALL,
    COPY,
    CUT,
    PASTE,
    TOGGLE_HIDDEN,
    NEW_FOLDER,
    RENAME,
    DELETE,
    REFRESH,
    OPEN,
    CLEAR_SELECTION,
    UP;

    static FileKeyboardCommand fromShortcut(
            final int keyCode,
            final KeyEvent event) {
        if (event == null || !event.isCtrlPressed()) {
            return NONE;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_F:
                return FIND;
            case KeyEvent.KEYCODE_L:
                return FOCUS_LOCATION;
            case KeyEvent.KEYCODE_A:
                return SELECT_ALL;
            case KeyEvent.KEYCODE_C:
                return COPY;
            case KeyEvent.KEYCODE_X:
                return CUT;
            case KeyEvent.KEYCODE_V:
                return PASTE;
            case KeyEvent.KEYCODE_H:
                return TOGGLE_HIDDEN;
            case KeyEvent.KEYCODE_N:
                return event.isShiftPressed() ? NEW_FOLDER : NEW_WINDOW;
            default:
                return NONE;
        }
    }

    static FileKeyboardCommand fromKeyDown(
            final int keyCode,
            final KeyEvent event) {
        if (event == null || event.getRepeatCount() != 0) {
            return NONE;
        }
        if (event.isAltPressed() && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            return UP;
        }
        if (event.hasNoModifiers()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_F2:
                    return RENAME;
                case KeyEvent.KEYCODE_FORWARD_DEL:
                    return DELETE;
                case KeyEvent.KEYCODE_F5:
                    return REFRESH;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    return OPEN;
                case KeyEvent.KEYCODE_ESCAPE:
                    return CLEAR_SELECTION;
                case KeyEvent.KEYCODE_DEL:
                    return UP;
                default:
                    break;
            }
        }
        return NONE;
    }

    static FileKeyboardCommand fromEvent(final KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return NONE;
        }
        final FileKeyboardCommand shortcut = fromShortcut(
                event.getKeyCode(), event);
        return shortcut != NONE
                ? shortcut
                : fromKeyDown(event.getKeyCode(), event);
    }
}
