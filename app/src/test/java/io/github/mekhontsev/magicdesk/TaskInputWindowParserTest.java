package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TaskInputWindowParserTest {
    private static final String DUMP =
            "  Display: 155\n"
                    + "    Windows:\n"
                    + "      1: name=Embedded{DragResizeInputListener of Surface("
                    + "name=Decor container of Task=6362)/@0x2abee7d}, id=49776, "
                    + "displayId=155, inputConfig=NOT_FOCUSABLE | TRUSTED_OVERLAY | SPY, "
                    + "alpha=1, frame=[340,240][1140,840], globalScale=0, "
                    + "touchableRegion=[316,216][364,230]|[1116,216][1164,230], "
                    + "ownerPid=16659, ownerUid=10454, dispatchingTimeout=8000ms, "
                    + "token=0xb4000076784b4010, touchOcclusionMode=BLOCK_UNTRUSTED\n"
                    + "      2: name=Embedded{Caption of Task=6362}, id=49778, "
                    + "displayId=155, inputConfig=NOT_FOCUSABLE | TRUSTED_OVERLAY, "
                    + "alpha=1, frame=[340,240][1140,280], globalScale=0, "
                    + "touchableRegion=[340,240][1140,280], ownerPid=16659, "
                    + "ownerUid=10454, dispatchingTimeout=8000ms, "
                    + "token=0xb40000767847a510, touchOcclusionMode=BLOCK_UNTRUSTED\n";

    @Test
    public void parsesCaptionInputWindow() {
        final TaskInputWindowParser.Entry entry =
                TaskInputWindowParser.findCaption(DUMP, 6362);

        assertNotNull(entry);
        assertEquals(155, entry.displayId);
        assertEquals("[340,240][1140,280]", entry.frame.toString());
        assertTrue(entry.hasConfig("TRUSTED_OVERLAY"));
        assertFalse(entry.hasConfig("NOT_VISIBLE"));
        assertTrue(entry.hasInputChannel());
        assertTrue(entry.hasTouchableRegion());
        assertEquals("0xb40000767847a510", entry.token);
    }

    @Test
    public void parsesResizeInputWindow() {
        final TaskInputWindowParser.Entry entry =
                TaskInputWindowParser.findResize(DUMP, 6362);

        assertNotNull(entry);
        assertEquals(155, entry.displayId);
        assertEquals("[340,240][1140,840]", entry.frame.toString());
        assertTrue(entry.hasConfig("SPY"));
        assertTrue(entry.hasInputChannel());
        assertTrue(entry.hasTouchableRegion());
    }

    @Test
    public void ignoresOtherTasksAndMalformedWindows() {
        assertNull(TaskInputWindowParser.findCaption(DUMP, 99));
        assertNull(TaskInputWindowParser.findResize(
                "name=Embedded{DragResizeInputListener of Surface("
                        + "name=Decor container of Task=6362)/@0x1}, "
                        + "displayId=bad, frame=[0,0][1,1]\n",
                6362));
    }

    @Test
    public void rejectsNullInputChannelToken() {
        final TaskInputWindowParser.Entry entry =
                TaskInputWindowParser.findCaption(
                        DUMP.replace("token=0xb40000767847a510",
                                "token=0x0"),
                        6362);

        assertNotNull(entry);
        assertFalse(entry.hasInputChannel());
    }
}
