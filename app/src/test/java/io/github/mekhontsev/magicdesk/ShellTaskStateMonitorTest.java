package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ShellTaskStateMonitorTest {
    @Test
    public void taskStackFingerprintIsStableForUnchangedSamples() {
        final List<ShellTaskStateMonitor.TaskWindowState> first = Arrays.asList(
                task(10, true, true, 1),
                task(11, true, false, 5));
        final List<ShellTaskStateMonitor.TaskWindowState> second = Arrays.asList(
                task(10, true, true, 1),
                task(11, true, false, 5));

        assertEquals(
                ShellTaskStateMonitor.taskStackFingerprint(first),
                ShellTaskStateMonitor.taskStackFingerprint(second));
    }

    @Test
    public void taskStackFingerprintDetectsUnreportedForegroundChange() {
        final List<Long> freeformForeground =
                ShellTaskStateMonitor.taskStackFingerprint(Arrays.asList(
                        task(11, true, true, 5),
                        task(10, true, false, 1)));
        final List<Long> fullscreenForeground =
                ShellTaskStateMonitor.taskStackFingerprint(Arrays.asList(
                        task(10, true, true, 1),
                        task(11, true, false, 5)));

        assertNotEquals(freeformForeground, fullscreenForeground);
    }

    @Test
    public void taskStackFingerprintDetectsModeAndVisibilityChanges() {
        final List<Long> original =
                ShellTaskStateMonitor.taskStackFingerprint(
                        Collections.singletonList(
                                task(10, true, true, 5)));

        assertNotEquals(original,
                ShellTaskStateMonitor.taskStackFingerprint(
                        Collections.singletonList(
                                task(10, true, true, 1))));
        assertNotEquals(original,
                ShellTaskStateMonitor.taskStackFingerprint(
                        Collections.singletonList(
                                task(10, false, true, 5))));
    }

    private static ShellTaskStateMonitor.TaskWindowState task(
            final int taskId,
            final boolean visible,
            final boolean focused,
            final int windowingMode) {
        return new ShellTaskStateMonitor.TaskWindowState(
                null,
                taskId,
                visible,
                focused,
                0,
                windowingMode,
                1,
                null,
                null,
                "example.app",
                "example.app",
                new Rect(0, 0, 100, 100));
    }
}
