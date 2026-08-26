package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSelfTestTaskHierarchyTest {
    @Test
    public void parseReadsExactHierarchyFields() throws Exception {
        final DesktopSelfTestTaskHierarchy.Snapshot snapshot =
                DesktopSelfTestTaskHierarchy.parse(
                        "task-hierarchy task=42 display=7 feature=20001"
                                + " mode=5 visible=true focused=true\n");

        assertEquals(42, snapshot.taskId);
        assertEquals(7, snapshot.displayId);
        assertEquals(20001, snapshot.featureId);
        assertEquals(5, snapshot.windowingMode);
        assertTrue(snapshot.visible);
        assertTrue(snapshot.focused);
    }
}
