package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
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
}
