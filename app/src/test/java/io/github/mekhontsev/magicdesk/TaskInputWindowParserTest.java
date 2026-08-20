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
                    + "token=0xb40000767847a510, touchOcclusionMode=BLOCK_UNTRUSTED\n"
                    + "      3: name=Maximize Menu for Task=6362, id=49780, "
                    + "displayId=155, inputConfig=TRUSTED_OVERLAY, alpha=1, "
                    + "frame=[824,280][1080,388], globalScale=0, "
                    + "touchableRegion=[824,280][1080,388], ownerPid=16659, "
                    + "ownerUid=1000, dispatchingTimeout=8000ms, "
                    + "token=0xb40000767847b120, touchOcclusionMode=BLOCK_UNTRUSTED\n";

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
    public void parsesMaximizeMenuInputWindow() {
        final TaskInputWindowParser.Entry entry =
                TaskInputWindowParser.findMaximizeMenu(DUMP, 6362);

        assertNotNull(entry);
        assertEquals(155, entry.displayId);
        assertEquals("[824,280][1080,388]", entry.frame.toString());
        assertTrue(entry.hasInputChannel());
        assertTrue(entry.hasTouchableRegion());
    }

    @Test
    public void parsesEmbeddedMaximizeMenuInputWindow() {
        final TaskInputWindowParser.Entry entry =
                TaskInputWindowParser.findMaximizeMenu(
                        DUMP.replace(
                                "name=Maximize Menu for Task=6362",
                                "name=Embedded{Maximize Menu for Task=6362}"),
                        6362);

        assertNotNull(entry);
        assertEquals(155, entry.displayId);
    }

    @Test
    public void ignoresInputWindowsClonedToMirrorDisplay() {
        final String mirrored = DUMP.replace(
                "displayId=155, inputConfig=",
                "displayId=265, inputConfig=CLONE | ") + DUMP;

        assertEquals(155,
                TaskInputWindowParser.findCaption(mirrored, 6362).displayId);
        assertEquals(155,
                TaskInputWindowParser.findResize(mirrored, 6362).displayId);
        assertEquals(155,
                TaskInputWindowParser.findMaximizeMenu(mirrored, 6362).displayId);
    }

    @Test
    public void ignoresOtherTasksAndMalformedWindows() {
        assertNull(TaskInputWindowParser.findCaption(DUMP, 99));
        assertNull(TaskInputWindowParser.findResize(
                "name=Embedded{DragResizeInputListener of Surface("
                        + "name=Decor container of Task=6362)/@0x1}, "
                        + "displayId=bad, frame=[0,0][1,1]\n",
                6362));
        assertNull(TaskInputWindowParser.findMaximizeMenu(DUMP, 99));
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

    @Test
    public void identifiesCurrentFocusedTaskAndIgnoresStaleAnrState() {
        final String focusDump =
                "Input Dispatcher State:\n"
                        + "  FocusedApplications:\n"
                        + "    displayId=155, name='ActivityRecord{123 u0 "
                        + "example/.Editor t6362}', dispatchingTimeout=8000ms\n"
                        + "  FocusedWindows:\n"
                        + "    displayId=155, name='abc example/.Editor'\n"
                        + "  FocusRequests:\n"
                        + "Input Dispatcher State at time of last ANR:\n"
                        + "  FocusedApplications:\n"
                        + "    displayId=155, name='ActivityRecord{456 u0 "
                        + "example/.Editor t99}', dispatchingTimeout=8000ms\n"
                        + "  FocusedWindows:\n"
                        + "    displayId=155, name='def example/.Editor'\n"
                        + "  FocusRequests:\n";

        assertTrue(TaskInputWindowParser.isTaskFocused(
                focusDump, 155, 6362));
        assertFalse(TaskInputWindowParser.isTaskFocused(
                focusDump, 155, 99));
        assertFalse(TaskInputWindowParser.isTaskFocused(
                focusDump, 0, 6362));
    }

    @Test
    public void requiresFocusedWindowOnTargetDisplay() {
        final String focusDump =
                "Input Dispatcher State:\n"
                        + "  FocusedApplications:\n"
                        + "    displayId=155, name='ActivityRecord{123 u0 "
                        + "example/.Editor t6362}', dispatchingTimeout=8000ms\n"
                        + "  FocusedWindows: <none>\n"
                        + "  FocusRequests:\n";

        assertFalse(TaskInputWindowParser.isTaskFocused(
                focusDump, 155, 6362));
    }

    @Test
    public void usesFocusedWindowTaskWhenFocusedApplicationIsStale() {
        final String focusDump =
                "Input Dispatcher State:\n"
                        + "  FocusedApplications:\n"
                        + "    displayId=155, name='ActivityRecord{123 u0 "
                        + "magicdesk/.ConsoleSeedActivity t12}', "
                        + "dispatchingTimeout=8000ms\n"
                        + "  FocusedWindows:\n"
                        + "    displayId=155, name='abc example/.Editor'\n"
                        + "  FocusRequests:\n"
                        + "  Display: 155\n"
                        + "    Windows:\n"
                        + "      0: name=abc example/.Editor, id=42, "
                        + "displayId=155, inputConfig=0x0, alpha=1, "
                        + "applicationInfo.name=ActivityRecord{456 u0 "
                        + "example/.Editor t6362}, applicationInfo.token=x\n";

        assertTrue(TaskInputWindowParser.isTaskFocused(
                focusDump, 155, 6362));
        assertFalse(TaskInputWindowParser.isTaskFocused(
                focusDump, 155, 12));
        assertEquals(6362,
                TaskInputWindowParser.findFocusedTaskId(focusDump, 155));
        assertEquals(-1,
                TaskInputWindowParser.findFocusedTaskId(focusDump, 0));
        assertEquals(
                "applicationTask=12, windowTask=6362, focusedWindow=true, "
                        + "bytes=" + focusDump.length()
                        + ", truncated=false",
                TaskInputWindowParser.describeFocus(focusDump, 155));
    }

    @Test
    public void doesNotGuessFocusedTaskWithoutWindowMapping() {
        final String focusDump =
                "Input Dispatcher State:\n"
                        + "  FocusedApplications:\n"
                        + "    displayId=155, name='ActivityRecord{123 u0 "
                        + "example/.Editor t6362}', dispatchingTimeout=8000ms\n"
                        + "  FocusedWindows:\n"
                        + "    displayId=155, name='abc example/.Editor'\n"
                        + "  FocusRequests:\n";

        assertTrue(TaskInputWindowParser.isTaskFocused(
                focusDump, 155, 6362));
        assertEquals(-1,
                TaskInputWindowParser.findFocusedTaskId(focusDump, 155));
    }

    @Test
    public void mapsFocusedWindowWithoutTextTitle() {
        final String focusDump =
                "Input Dispatcher State:\n"
                        + "  FocusedApplications:\n"
                        + "  FocusedWindows:\n"
                        + "    displayId=155, name='abc'\n"
                        + "  FocusRequests:\n"
                        + "  Display: 155\n"
                        + "    Windows:\n"
                        + "      0: name=abc, id=42, displayId=155, "
                        + "inputConfig=0x0, alpha=1, "
                        + "applicationInfo.name=ActivityRecord{456 u0 "
                        + "example/.Editor t6362}, applicationInfo.token=x\n";

        assertEquals(6362,
                TaskInputWindowParser.findFocusedTaskId(focusDump, 155));
    }
}
