package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import io.github.mekhontsev.magicdesk.hosted.HostedTextState;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class HostedEditorEditsTest {
    private final HostedEditorEdits edits = new HostedEditorEdits();
    private final ArrayList<Long> revisions = new ArrayList<>();

    private boolean see(long revision, String text, int cursor, int anchor) {
        return edits.observe(new HostedTextState(1, revision, HostedTextState.Purpose.NORMAL, 0,
                text, cursor, anchor, null, true));
    }
    private void insert(String value) {
        var t = edits.text();
        assertTrue(edits.send(t.replace(t.start(), t.end(), value), s -> revisions.add(s.revision())));
    }
    private void backspace() {
        var t = edits.text();
        var range = HostedTextState.Range.surrounding(t.value(), t.start(), t.end(), 1, 0, true);
        assertTrue(edits.sendAtConfirmedCaret(t.delete(range), s -> revisions.add(s.revision())));
    }

    @Test public void replacingSelectionKeepsSuffixAcrossCoalescedAcknowledgements() {
        see(1, "(abc)", 1, 4);
        for (String s : List.of("x", "yz", "1", "2")) insert(s);
        assertEquals(List.of(1L,1L,1L,1L), revisions);
        assertTrue(see(2, "(xyz)", 4, 4));
        assertEquals("(xyz12)", edits.text().value());
        assertTrue(see(3, "(xyz12)", 6, 6));
        assertFalse(edits.pending());
    }

    @Test public void retypingADeletedCharacterCannotHideItsAcknowledgement() {
        see(1, "a", 1, 1);
        backspace();
        insert("a");
        backspace();
        assertEquals(List.of(1L), revisions);
        assertTrue(see(2, "", 0, 0));
        assertEquals(List.of(1L,2L), revisions);
        assertTrue(see(3, "a", 1, 1));
        assertEquals(List.of(1L,2L,3L), revisions);
        assertTrue(see(4, "", 0, 0));
        assertFalse(edits.pending());
    }

    @Test public void transformedGuestTextInvalidatesDependentDeletion() {
        see(1, "", 0, 0);
        insert("ab"); insert("c"); insert("d"); backspace();
        assertFalse(see(2, "ABC", 3, 3));
        assertEquals(List.of(1L,1L,1L), revisions);
        assertEquals("ABC", edits.text().value());
        assertFalse(edits.pending());
        assertTrue(edits.takeCorrection());
    }
}
