package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

public final class AppPresentationRuntimeControllerTest {
    private final RecordingApplier mApplier = new RecordingApplier();

    @Before
    public void setUp() {
        DesktopStateStore.useStorageForTests(
                new DesktopStateStore.Storage() {
                    private String mEncoded = "";

                    @Override
                    public String read() {
                        return mEncoded;
                    }

                    @Override
                    public void write(final String encoded) {
                        mEncoded = encoded;
                    }
                });
    }

    @After
    public void tearDown() {
        DesktopStateStore.useStorageForTests(null);
    }

    @Test
    public void existingTaskIsUpdatedOncePerEffectiveDensity() {
        assertTrue(AppPresentationProfileStore.setScale(
                "example.application", 125));
        final AppPresentationRuntimeController controller =
                new AppPresentationRuntimeController(mApplier, null);
        controller.start();
        final TaskRepository.TaskEntry task = task(42, 160);

        controller.observe(Collections.singletonList(task), 160);
        controller.observe(Collections.singletonList(task), 160);

        assertEquals(1, mApplier.calls);
        assertEquals(42, mApplier.taskIds[0]);
        assertEquals(200, mApplier.densityDpi);

        controller.observe(Collections.singletonList(task), 200);

        assertEquals(2, mApplier.calls);
        assertEquals(250, mApplier.densityDpi);
    }

    @Test
    public void matchingSnapshotDoesNotSubmitRedundantTransaction() {
        assertTrue(AppPresentationProfileStore.setScale(
                "example.application", 125));
        final AppPresentationRuntimeController controller =
                new AppPresentationRuntimeController(mApplier, null);
        controller.start();

        controller.observe(
                Collections.singletonList(task(42, 200)), 160);

        assertEquals(0, mApplier.calls);
    }

    @Test
    public void laterFrameworkResetCreatesOneNewAttempt() {
        assertTrue(AppPresentationProfileStore.setScale(
                "example.application", 125));
        final AppPresentationRuntimeController controller =
                new AppPresentationRuntimeController(mApplier, null);
        controller.start();

        controller.observe(
                Collections.singletonList(task(42, 160)), 160);
        controller.observe(
                Collections.singletonList(task(42, 200)), 160);
        controller.observe(
                Collections.singletonList(task(42, 160)), 160);
        controller.observe(
                Collections.singletonList(task(42, 160)), 160);

        assertEquals(2, mApplier.calls);
        assertEquals(200, mApplier.densityDpi);
    }

    @Test
    public void explicitResetUsesInheritedDensity() {
        assertTrue(AppPresentationProfileStore.setScale(
                "example.application", 125));
        final AppPresentationRuntimeController controller =
                new AppPresentationRuntimeController(mApplier, null);
        controller.start();
        assertTrue(AppPresentationProfileStore.reset(
                "example.application"));

        assertTrue(controller.applyStoredPackage(
                "example.application",
                DesktopTaskDensity.INHERIT,
                Collections.singletonList(task(42, 200)),
                null));

        assertEquals(1, mApplier.calls);
        assertEquals(DesktopTaskDensity.INHERIT, mApplier.densityDpi);
    }

    private static TaskRepository.TaskEntry task(
            final int taskId,
            final int densityDpi) {
        return new TaskRepository.TaskEntry(
                taskId,
                taskId,
                3,
                "example.application",
                "example.application/.MainActivity",
                "example.application/.MainActivity",
                "freeform",
                new Rect(0, 0, 800, 600),
                FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD,
                densityDpi,
                false,
                true,
                true);
    }

    private static final class RecordingApplier
            implements AppPresentationRuntimeController.DensityApplier {
        int calls;
        int[] taskIds;
        int densityDpi;

        @Override
        public boolean apply(
                final int[] candidateTaskIds,
                final int candidateDensityDpi,
                final TaskRepository.ActionCallback callback) {
            calls++;
            taskIds = candidateTaskIds.clone();
            densityDpi = candidateDensityDpi;
            if (callback != null) {
                callback.onComplete(new TaskRepository.ActionResult(
                        true, "applied"));
            }
            return true;
        }
    }
}
