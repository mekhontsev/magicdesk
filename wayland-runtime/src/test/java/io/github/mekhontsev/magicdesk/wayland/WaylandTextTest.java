package io.github.mekhontsev.magicdesk.wayland;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WaylandTextTest {
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
