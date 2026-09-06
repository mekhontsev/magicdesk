package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class DesktopTaskReturnResultTest {
    @Test
    public void acceptsCompleteReturnAndEmptyWorkspace() {
        assertTrue(DesktopTaskReturnResult.succeeded(
                DesktopTaskReturnResult.encode(4, 2, 0), 4));
        assertTrue(DesktopTaskReturnResult.succeeded(
                DesktopTaskReturnResult.encode(4, 0, 0), 4));
    }

    @Test
    public void rejectsPartialAndTotalFailureDespiteLegacySummary() {
        assertFalse(DesktopTaskReturnResult.succeeded(
                "tasks-returned=1 failed=1\n" + DesktopTaskReturnResult.encode(4, 1, 1), 4));
        assertFalse(DesktopTaskReturnResult.succeeded(
                DesktopTaskReturnResult.encode(4, 0, 2), 4));
    }

    @Test
    public void rejectsUnknownIncompleteAndWrongDisplayResults() {
        assertFalse(DesktopTaskReturnResult.succeeded("tasks-returned=0 failed=1", 4));
        assertFalse(DesktopTaskReturnResult.succeeded("{\"success\":true}", 4));
        assertFalse(DesktopTaskReturnResult.succeeded(null, 4));
        assertFalse(DesktopTaskReturnResult.succeeded(
                DesktopTaskReturnResult.encode(5, 1, 0), 4));
    }
}
