package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class FrameworkTaskObservationSourceTest {
    @Test
    public void taskStackFingerprintIsStableForUnchangedSamples() {
        final List<FrameworkTaskSnapshot> first =
                Arrays.asList(
                        task(10, true, true, 1),
                        task(11, true, false, 5));
        final List<FrameworkTaskSnapshot> second =
                Arrays.asList(
                        task(10, true, true, 1),
                        task(11, true, false, 5));

        assertEquals(
                FrameworkTaskObservationSource.taskStackFingerprint(first),
                FrameworkTaskObservationSource.taskStackFingerprint(second));
    }

    @Test
    public void taskStackFingerprintDetectsUnreportedForegroundChange() {
        final List<Long> freeformForeground =
                FrameworkTaskObservationSource.taskStackFingerprint(Arrays.asList(
                        task(11, true, true, 5),
                        task(10, true, false, 1)));
        final List<Long> fullscreenForeground =
                FrameworkTaskObservationSource.taskStackFingerprint(Arrays.asList(
                        task(10, true, true, 1),
                        task(11, true, false, 5)));

        assertNotEquals(freeformForeground, fullscreenForeground);
    }

    @Test
    public void taskStackFingerprintDetectsModeAndVisibilityChanges() {
        final List<Long> original =
                FrameworkTaskObservationSource.taskStackFingerprint(
                        Collections.singletonList(
                                task(10, true, true, 5)));

        assertNotEquals(original,
                FrameworkTaskObservationSource.taskStackFingerprint(
                        Collections.singletonList(
                                task(10, true, true, 1))));
        assertNotEquals(original,
                FrameworkTaskObservationSource.taskStackFingerprint(
                        Collections.singletonList(
                                task(10, false, true, 5))));
    }

    private static FrameworkTaskSnapshot task(
            final int taskId,
            final boolean visible,
            final boolean focused,
            final int windowingMode) {
        return new FrameworkTaskSnapshot(
                null,
                taskId,
                taskId,
                2,
                1,
                windowingMode,
                1,
                null,
                null,
                "",
                "",
                "example.app",
                "example.app",
                -1,
                null,
                new Rect(0, 0, 100, 100),
                visible,
                focused,
                Integer.valueOf(0));
    }
}
