package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class KeyboardShortcutStateMachineTest {
    private static String key(final String name, final String action) {
        return "[ 1.000] /dev/input/event1: EV_KEY " + name + " " + action;
    }

    @Test
    public void ctrlSpaceWorksInLimitedMode() {
        final KeyboardShortcutStateMachine state =
                new KeyboardShortcutStateMachine();
        state.accept(key("KEY_LEFTCTRL", "DOWN"), false);

        assertEquals(
                KeyboardShortcutStateMachine.Action.TOGGLE_LAYOUT,
                state.accept(key("KEY_SPACE", "DOWN"), false));
    }

    @Test
    public void windowCommandsRequireFullMode() {
        final KeyboardShortcutStateMachine state =
                new KeyboardShortcutStateMachine();
        state.accept(key("KEY_LEFTMETA", "DOWN"), false);
        assertEquals(
                KeyboardShortcutStateMachine.Action.NONE,
                state.accept(key("KEY_UP", "DOWN"), false));
        assertEquals(
                KeyboardShortcutStateMachine.Action.FULLSCREEN,
                state.accept(key("KEY_UP", "DOWN"), true));
    }

    @Test
    public void releasingAltCommitsActiveAltTab() {
        final KeyboardShortcutStateMachine state =
                new KeyboardShortcutStateMachine();
        assertEquals(
                KeyboardShortcutStateMachine.Action.ALT_TAB_FORWARD,
                state.accept("MAGICDESK_ALT_TAB_ADVANCE forward", true));
        assertEquals(
                KeyboardShortcutStateMachine.Action.ALT_TAB_COMMIT,
                state.accept(key("KEY_LEFTALT", "UP"), true));
    }

    @Test
    public void resetReportsPendingAltTab() {
        final KeyboardShortcutStateMachine state =
                new KeyboardShortcutStateMachine();
        state.accept("MAGICDESK_ALT_TAB_ADVANCE reverse", true);

        assertTrue(state.reset());
        assertEquals(
                KeyboardShortcutStateMachine.Action.NONE,
                state.accept("MAGICDESK_ALT_TAB_COMMIT", true));
    }

    @Test
    public void limitedModeKeepsOnlyGlobalShortcuts() {
        final KeyboardShortcutStateMachine state =
                new KeyboardShortcutStateMachine();

        state.accept(key("KEY_LEFTMETA", "DOWN"), false);
        assertEquals(
                KeyboardShortcutStateMachine.Action.SHOW_DESKTOP,
                state.accept(key("KEY_D", "DOWN"), false));
        assertEquals(
                KeyboardShortcutStateMachine.Action.NONE,
                state.accept(key("KEY_N", "DOWN"), false));
        state.accept(key("KEY_LEFTMETA", "UP"), false);
        assertEquals(
                KeyboardShortcutStateMachine.Action.DISMISS,
                state.accept(key("KEY_ESC", "DOWN"), false));
        assertEquals(
                KeyboardShortcutStateMachine.Action.NONE,
                state.accept("MAGICDESK_ALT_TAB_ADVANCE forward", false));
    }

    @Test
    public void fullModeMapsWindowAndSystemShortcuts() {
        final KeyboardShortcutStateMachine state =
                new KeyboardShortcutStateMachine();

        assertMetaAction(state, "KEY_BACKSPACE",
                KeyboardShortcutStateMachine.Action.BACK);
        assertMetaAction(state, "KEY_L",
                KeyboardShortcutStateMachine.Action.LOCK);
        assertMetaAction(state, "KEY_N",
                KeyboardShortcutStateMachine.Action.NOTIFICATIONS);
        assertMetaAction(state, "KEY_Q",
                KeyboardShortcutStateMachine.Action.SYSTEM);
        assertMetaAction(state, "KEY_I",
                KeyboardShortcutStateMachine.Action.SETTINGS);
        assertMetaAction(state, "KEY_UP",
                KeyboardShortcutStateMachine.Action.FULLSCREEN);
        assertMetaAction(state, "KEY_DOWN",
                KeyboardShortcutStateMachine.Action.RESTORE);
        assertMetaAction(state, "KEY_LEFT",
                KeyboardShortcutStateMachine.Action.SNAP_LEFT);
        assertMetaAction(state, "KEY_RIGHT",
                KeyboardShortcutStateMachine.Action.SNAP_RIGHT);
        assertMetaAction(state, "KEY_SYSRQ",
                KeyboardShortcutStateMachine.Action.SCREENSHOT);
        assertMetaAction(state, "KEY_SLASH",
                KeyboardShortcutStateMachine.Action.SHORTCUT_HELP);

        state.reset();
        state.accept(key("KEY_LEFTALT", "DOWN"), true);
        assertEquals(
                KeyboardShortcutStateMachine.Action.CLOSE,
                state.accept(key("KEY_F4", "DOWN"), true));
    }

    @Test
    public void repeatsAndExtraModifiersDoNotTriggerShortcuts() {
        final KeyboardShortcutStateMachine state =
                new KeyboardShortcutStateMachine();
        state.accept(key("KEY_LEFTMETA", "DOWN"), true);

        assertEquals(
                KeyboardShortcutStateMachine.Action.NONE,
                state.accept(key("KEY_UP", "REPEAT"), true));
        state.accept(key("KEY_LEFTSHIFT", "DOWN"), true);
        assertEquals(
                KeyboardShortcutStateMachine.Action.NONE,
                state.accept(key("KEY_UP", "DOWN"), true));
        assertFalse(state.reset());
        assertEquals(
                KeyboardShortcutStateMachine.Action.NONE,
                state.accept(key("KEY_D", "DOWN"), true));
    }

    @Test
    public void metaShiftPrintScreenTogglesRecording() {
        final KeyboardShortcutStateMachine state =
                new KeyboardShortcutStateMachine();
        state.accept(key("KEY_LEFTMETA", "DOWN"), true);
        state.accept(key("KEY_LEFTSHIFT", "DOWN"), true);

        assertEquals(
                KeyboardShortcutStateMachine.Action.SCREEN_RECORDING,
                state.accept(key("KEY_SYSRQ", "DOWN"), true));
    }

    private static void assertMetaAction(
            final KeyboardShortcutStateMachine state,
            final String keyName,
            final KeyboardShortcutStateMachine.Action expected) {
        state.reset();
        state.accept(key("KEY_LEFTMETA", "DOWN"), true);
        assertEquals(expected, state.accept(key(keyName, "DOWN"), true));
    }
}
