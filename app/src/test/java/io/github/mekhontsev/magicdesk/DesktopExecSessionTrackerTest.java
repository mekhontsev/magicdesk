package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopExecSessionTrackerTest {
    @Test
    public void repeatedCommandExecutionsHaveIndependentCompletion() {
        final DesktopLaunchRequest request = request();
        final String first = DesktopExecSessionTracker.begin(request);
        final String second = DesktopExecSessionTracker.begin(request);
        try {
            assertNotEquals(first, second);
            DesktopExecSessionTracker.running(first);
            DesktopExecSessionTracker.running(second);
            assertTrue(DesktopExecSessionTracker.diagnostics().contains("active=2,"));
            DesktopExecSessionTracker.finished(first);
            assertTrue(DesktopExecSessionTracker.diagnostics().contains("active=1,"));
        } finally {
            DesktopExecSessionTracker.finished(first);
            DesktopExecSessionTracker.finished(second);
        }
    }

    @Test
    public void completionBeforeStartReplyCannotBecomeRunningAgain() {
        final String id = DesktopExecSessionTracker.begin(request());
        DesktopExecSessionTracker.finished(id);
        DesktopExecSessionTracker.running(id);
        assertTrue(DesktopExecSessionTracker.diagnostics().endsWith(id + ":finished"));
    }

    @Test
    public void evictedExecutionCannotModifyALaterOne() {
        final String old = DesktopExecSessionTracker.begin(request());
        String latest = "";
        for (int index = 0; index < 32; index++) {
            latest = DesktopExecSessionTracker.begin(request());
            DesktopExecSessionTracker.delegated(latest);
        }
        DesktopExecSessionTracker.failed(old);
        assertTrue(DesktopExecSessionTracker.diagnostics().startsWith("tracked=32,"));
        assertTrue(DesktopExecSessionTracker.diagnostics().endsWith(latest + ":delegated"));
    }

    private static DesktopLaunchRequest request() {
        return new DesktopLaunchRequest("Command", "", null, null,
                new DesktopExecSpec(DesktopExecBackend.SHELL, "true", false),
                null, null, "");
    }
}
