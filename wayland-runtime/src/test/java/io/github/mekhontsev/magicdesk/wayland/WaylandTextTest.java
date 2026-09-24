package io.github.mekhontsev.magicdesk.wayland;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WaylandTextTest {
    @Test public void purposesAreDecodedAtTheProtocolBoundary() {
        assertEquals(io.github.mekhontsev.magicdesk.hosted.HostedTextState.Purpose.EMAIL,
                WaylandText.state(1, 0, null, 0, 0, 6, 0).purpose());
        assertEquals(io.github.mekhontsev.magicdesk.hosted.HostedTextState.Purpose.TERMINAL,
                WaylandText.state(1, 0, null, 0, 0, 13, 0).purpose());
        assertThrows(IllegalArgumentException.class, () -> WaylandText.state(1, 0, null, 0, 0, 14, 0));
    }
    @Test public void surroundingOffsetsAndDeletionExcludeSelection() {
        var state = WaylandText.state(5, 17, "a\u0416\ud83d\ude00z".getBytes(StandardCharsets.UTF_8), 7, 3, 6, 1);
        assertEquals("a\u0416\ud83d\ude00z", state.surrounding());
        assertEquals(4, state.cursor());
        assertEquals(2, state.anchor());
        assertEquals(new WaylandText.Deletion(2, 1), WaylandText.deletion(state, 1, 1, false));
        assertEquals(new WaylandText.Deletion(3, 1), WaylandText.deletion(state, Integer.MAX_VALUE, Integer.MAX_VALUE, true));
    }
    @Test public void deletionNeverSplitsSupplementaryCharacter() {
        var state = WaylandText.state(5, 1, "\ud83d\ude00".getBytes(StandardCharsets.UTF_8), 4, 4, 0, 0);
        assertEquals(new WaylandText.Deletion(4, 0), WaylandText.deletion(state, 1, 0, false));
        assertEquals(new WaylandText.Deletion(4, 0), WaylandText.deletion(state, 1, 0, true));
        assertEquals(new WaylandText.Deletion(0, 0), WaylandText.deletion(state, 0, 0, false));
    }
    @Test public void unavailableContextIsNotEmptyAndDisabledIsNotAnEditor() {
        assertNull(WaylandText.state(0, 0, null, 0, 0, 0, 0));
        var missing = WaylandText.state(1, 0, null, 0, 0, 8, 0);
        assertTrue(missing.privateText());
        assertEquals(-1, missing.cursor());
        assertNull(WaylandText.deletion(missing, 1, 0, false));
        var empty = WaylandText.state(1, 0, new byte[0], 0, 0, 8, 0);
        assertEquals(new WaylandText.Deletion(0, 0), WaylandText.deletion(empty, 1, 0, false));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectCursorInsideUtf8Character() {
        WaylandText.state(1, 0, "\u0416".getBytes(StandardCharsets.UTF_8), 1, 0, 0, 0);
    }
    @Test public void largeCommitPreservesAllCodePoints() {
        String text = ("a".repeat(3998) + "\u0416\ud83d\ude00").repeat(30);
        var edits = new ArrayList<WaylandText.Edit>();
        WaylandText.send(text, false, text.length(), edits::add);
        StringBuilder result = new StringBuilder();
        for (var edit : edits) {
            assertFalse(edit.composing());
            assertTrue(edit.text().length <= WaylandText.MAX_BYTES);
            assertEquals(edit.text().length, edit.cursor());
            result.append(new String(edit.text(), StandardCharsets.UTF_8));
        }
        assertEquals(text, result.toString());
    }

    @Test public void preeditUsesByteCursorAtCodePointBoundary() {
        var edits = new ArrayList<WaylandText.Edit>();
        WaylandText.send("\u0416\ud83d\ude00", true, 2, edits::add);
        assertEquals(2, edits.get(0).cursor());
        assertTrue(edits.get(0).composing());
        assertEquals(6, edits.get(0).text().length);
    }

    @Test public void oversizedPreeditClearsPreviewButCommitIsNotLost() {
        String text = "x".repeat(8001);
        var edits = new ArrayList<WaylandText.Edit>();
        WaylandText.send(text, true, text.length(), edits::add);
        assertEquals(0, edits.get(0).text().length);
        WaylandText.send(text, false, text.length(), edits::add);
        assertEquals(8001, edits.stream().mapToInt(e -> e.text().length).sum());
    }

    @Test public void emptyCommitClearsComposition() {
        var edits = new ArrayList<WaylandText.Edit>();
        WaylandText.send("", false, 0, edits::add);
        assertEquals(1, edits.size());
        assertEquals(0, edits.get(0).text().length);
    }
}
