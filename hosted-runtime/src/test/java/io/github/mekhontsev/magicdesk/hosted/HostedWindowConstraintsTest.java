package io.github.mekhontsev.magicdesk.hosted;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostedWindowConstraintsTest {
    @Test public void unconstrainedAndFixedClients() {
        assertEquals(640, HostedWindowConstraints.NONE.width(640));
        assertEquals(1, HostedWindowConstraints.NONE.height(-20));
        var fixed = new HostedWindowConstraints(225, 128, 225, 128);
        assertEquals(225, fixed.width(1280));
        assertEquals(128, fixed.height(20));
    }
    @Test public void independentAxesAndInvalidHints() {
        var limits = new HostedWindowConstraints(320, 0, 0, 600);
        assertEquals(320, limits.width(100));
        assertEquals(1920, limits.width(1920));
        assertEquals(600, limits.height(800));
        assertThrows(IllegalArgumentException.class, () -> new HostedWindowConstraints(400, 0, 300, 0));
        assertThrows(IllegalArgumentException.class, () -> new HostedWindowConstraints(-1, 0, 0, 0));
    }
    @Test public void editorGeometryDoesNotChangeItsIdentity() {
        var first = new HostedTextState(1, 2, HostedTextState.Purpose.NORMAL, 0, "abc", 1, 1,
                new HostedTextState.Caret(.1f, .2f, .1f, .3f));
        var second = new HostedTextState(1, 2, HostedTextState.Purpose.NORMAL, 0, "abc", 1, 1,
                new HostedTextState.Caret(-.1f, .2f, -.1f, .3f));
        assertTrue(first.sameEditor(second));
        assertNotEquals(first, second);
        assertThrows(IllegalArgumentException.class, () -> new HostedTextState.Caret(0, 0, Float.NaN, 1));
    }
    @Test public void editCauseDoesNotReplaceEditorIdentity() {
        var external = new HostedTextState(1, 2, HostedTextState.Purpose.NORMAL, 0, "abc", 3, 3);
        var ime = new HostedTextState(1, 3, HostedTextState.Purpose.NORMAL, 0, "abcd", 4, 4, null, true);
        assertFalse(external.inputMethodChange());
        assertTrue(ime.inputMethodChange());
        assertTrue(external.sameEditor(ime));
    }
}
