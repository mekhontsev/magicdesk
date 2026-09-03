package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    @Test
    public void freeformBoundsAreObservedPerTaskNotPerSavedIdentity() {
        final Map<Integer, FrameworkTaskObservationSource.FreeformBoundsState>
                bounds = FrameworkTaskObservationSource.collectFreeformBounds(
                        Arrays.asList(
                                task(10, true, true, 5),
                                task(11, true, false, 5)));

        assertEquals(2, bounds.size());
        assertTrue(bounds.containsKey(Integer.valueOf(10)));
        assertTrue(bounds.containsKey(Integer.valueOf(11)));
        assertEquals("example.app",
                bounds.get(Integer.valueOf(10)).stateKey);
    }

    @Test
    public void transientTopActivityDoesNotSuppressBoundsObservation() {
        final FrameworkTaskSnapshot task = task(
                10,
                "example.app",
                "android",
                "example.app/.MainActivity");
        final Map<Integer, FrameworkTaskObservationSource.FreeformBoundsState>
                bounds = FrameworkTaskObservationSource.collectFreeformBounds(
                        Collections.singletonList(task));

        assertTrue(bounds.containsKey(Integer.valueOf(10)));
        assertEquals("", bounds.get(Integer.valueOf(10)).stateKey);
    }

    @Test
    public void magicDeskInfrastructureIsNotAWindowBoundsSource() {
        final FrameworkTaskSnapshot task = task(
                10,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "/.DesktopShellActivity");

        assertFalse(FrameworkTaskObservationSource.collectFreeformBounds(
                Collections.singletonList(task)).containsKey(
                        Integer.valueOf(10)));
    }

    private static FrameworkTaskSnapshot task(
            final int taskId,
            final boolean visible,
            final boolean focused,
            final int windowingMode) {
        final FrameworkTaskSnapshot snapshot = new FrameworkTaskSnapshot(
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
        setBounds(snapshot.bounds, 0, 0, 100, 100);
        return snapshot;
    }

    private static FrameworkTaskSnapshot task(
            final int taskId,
            final String packageName,
            final String topPackage,
            final String componentName) {
        final FrameworkTaskSnapshot snapshot = new FrameworkTaskSnapshot(
                null,
                taskId,
                taskId,
                0,
                1,
                5,
                1,
                null,
                null,
                componentName,
                componentName,
                packageName,
                topPackage,
                -1,
                null,
                new Rect(0, 80, 600, 2300),
                true,
                true,
                Integer.valueOf(0));
        setBounds(snapshot.bounds, 0, 80, 600, 2300);
        return snapshot;
    }

    private static void setBounds(
            final Rect bounds,
            final int left,
            final int top,
            final int right,
            final int bottom) {
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
    }
}
