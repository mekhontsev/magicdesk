package io.github.mekhontsev.magicdesk;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.mekhontsev.magicdesk.KeyboardShortcutStateMachine.Action.*;

public final class KeyboardShortcutStateMachineTest {
    @Test public void altTabConsumesBothTabEdgesAndCommitsOnlyOnFinalAltRelease() {
        final KeyboardShortcutStateMachine s = new KeyboardShortcutStateMachine();
        assertFalse(s.accept(KeyEvent.KEYCODE_ALT_LEFT, true, 0, false, true, false, false).consumed);
        var r = s.accept(KeyEvent.KEYCODE_TAB, true, 0, false, true, false, false);
        assertTrue(r.consumed);
        assertEquals(ALT_TAB_FORWARD, r.action);
        assertTrue(s.accept(KeyEvent.KEYCODE_TAB, false, 0, false, true, false, false).consumed);
        assertEquals(NONE, s.accept(KeyEvent.KEYCODE_ALT_LEFT, false, 0, false, true, false, false).action);
        assertEquals(ALT_TAB_COMMIT, s.accept(KeyEvent.KEYCODE_ALT_RIGHT, false, 0, false, false, false, false).action);
        assertEquals(NONE, s.accept(KeyEvent.KEYCODE_ALT_RIGHT, false, 0, false, false, false, false).action);
    }

    @Test public void reverseCycleAndDisconnectCancelAreIndependent() {
        final KeyboardShortcutStateMachine a = new KeyboardShortcutStateMachine();
        final KeyboardShortcutStateMachine b = new KeyboardShortcutStateMachine();
        assertEquals(ALT_TAB_REVERSE, a.accept(KeyEvent.KEYCODE_TAB, true, 0, false, true, true, false).action);
        assertFalse(b.reset());
        assertTrue(a.reset());
        assertFalse(a.reset());
    }

    @Test public void shortcutRepeatsAreConsumedWithoutRepeatedCommands() {
        final KeyboardShortcutStateMachine s = new KeyboardShortcutStateMachine();
        assertEquals(RESTORE, s.accept(KeyEvent.KEYCODE_DPAD_DOWN, true, 0, false, false, false, true).action);
        var repeat = s.accept(KeyEvent.KEYCODE_DPAD_DOWN, true, 1, false, false, false, true);
        assertTrue(repeat.consumed);
        assertEquals(NONE, repeat.action);
        // The modifier may be released before its key; that key-up is still ours.
        assertTrue(s.accept(KeyEvent.KEYCODE_DPAD_DOWN, false, 0, false, false, false, false).consumed);
        assertFalse(s.accept(KeyEvent.KEYCODE_DPAD_DOWN, true, 0, false, false, false, false).consumed);
    }

    @Test public void ordinaryTextAndApplicationShortcutsPassUnchanged() {
        final KeyboardShortcutStateMachine s = new KeyboardShortcutStateMachine();
        for (int repeat = 0; repeat < 3; repeat++) {
            var r = s.accept(KeyEvent.KEYCODE_C, true, repeat, true, false, false, false);
            assertFalse(r.consumed);
            assertEquals(NONE, r.action);
        }
        assertFalse(s.accept(KeyEvent.KEYCODE_C, false, 0, false, false, false, false).consumed);
        var escape = s.accept(KeyEvent.KEYCODE_ESCAPE, true, 0, false, false, false, false);
        assertFalse(escape.consumed);
        assertEquals(DISMISS, escape.action);
        assertEquals(NONE, s.accept(KeyEvent.KEYCODE_DPAD_UP, true, 0, false, false, true, true).action);
    }

    @Test public void metaIsBalancedAndCannotTriggerTheSystemAssistant() {
        final KeyboardShortcutStateMachine s = new KeyboardShortcutStateMachine();
        assertTrue(s.accept(KeyEvent.KEYCODE_META_LEFT, true, 0, false, false, false, true).consumed);
        assertTrue(s.accept(KeyEvent.KEYCODE_META_LEFT, false, 0, false, false, false, false).consumed);
    }

    @Test public void allWindowAndSystemActionsRemainAvailable() {
        final int[] keys = {KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_N,
                KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_SYSRQ, KeyEvent.KEYCODE_SLASH};
        final KeyboardShortcutStateMachine.Action[] actions = {BACK, LOCK, NOTIFICATIONS,
                SYSTEM, SETTINGS, FULLSCREEN, RESTORE, SNAP_LEFT, SNAP_RIGHT, SHOW_DESKTOP,
                SCREENSHOT, SHORTCUT_HELP};
        for (int i = 0; i < keys.length; i++) {
            final KeyboardShortcutStateMachine s = new KeyboardShortcutStateMachine();
            assertEquals(actions[i], s.accept(keys[i], true, 0, false, false, false, true).action);
        }
        assertEquals(TOGGLE_LAYOUT, new KeyboardShortcutStateMachine().accept(
                KeyEvent.KEYCODE_SPACE, true, 0, true, false, false, false).action);
        assertEquals(CLOSE, new KeyboardShortcutStateMachine().accept(
                KeyEvent.KEYCODE_F4, true, 0, false, true, false, false).action);
        assertEquals(SCREEN_RECORDING, new KeyboardShortcutStateMachine().accept(
                KeyEvent.KEYCODE_SYSRQ, true, 0, false, false, true, true).action);
    }
}
