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

    private static final AppProfile PROFILE = new AppProfile(0, 0);
    private static final AppIdentity APP = PROFILE.application("example.application");

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
                APP, 125));
        final AppPresentationRuntimeController controller =
                new AppPresentationRuntimeController(PROFILE, mApplier, null);
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
                APP, 125));
        final AppPresentationRuntimeController controller =
                new AppPresentationRuntimeController(PROFILE, mApplier, null);
        controller.start();

        controller.observe(
                Collections.singletonList(task(42, 200)), 160);

        assertEquals(0, mApplier.calls);
    }

    @Test
    public void laterFrameworkResetCreatesOneNewAttempt() {
        assertTrue(AppPresentationProfileStore.setScale(
                APP, 125));
        final AppPresentationRuntimeController controller =
                new AppPresentationRuntimeController(PROFILE, mApplier, null);
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
                APP, 125));
        final AppPresentationRuntimeController controller =
                new AppPresentationRuntimeController(PROFILE, mApplier, null);
        controller.start();
        assertTrue(AppPresentationProfileStore.reset(
                APP));

        assertTrue(controller.applyStoredApplication(
                APP,
                DesktopTaskDensity.INHERIT,
                Collections.singletonList(task(42, 200)),
                null));

        assertEquals(1, mApplier.calls);
        assertEquals(DesktopTaskDensity.INHERIT, mApplier.densityDpi);
    }

    @Test
    public void samePackageInUnknownOrDifferentUserDoesNotReceiveCurrentProfileDensity() {
        assertTrue(AppPresentationProfileStore.setScale(APP, 125));
        final AppPresentationRuntimeController controller =
                new AppPresentationRuntimeController(PROFILE, mApplier, null);
        controller.start();
        final TaskRepository.TaskEntry unknown = taskForUser(-1);
        final TaskRepository.TaskEntry work = taskForUser(10);
        controller.observe(java.util.List.of(unknown, work), 160);
        assertTrue(controller.applyStoredApplication(APP, 200,
                java.util.List.of(unknown, work), null));
        assertEquals(0, mApplier.calls);
    }

    private static TaskRepository.TaskEntry taskForUser(final int userId) {
        return new TaskRepository.TaskEntry(42, 42, 3, APP.packageName,
                APP.packageName + "/.Main", APP.packageName + "/.Main", "freeform",
                new Rect(0, 0, 800, 600), FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD,
                160, false, true, true, userId);
    }

    private static TaskRepository.TaskEntry task(
            final int taskId,
            final int densityDpi) {
        return new TaskRepository.TaskEntry(
                taskId,
                taskId,
                3,
                APP.packageName,
                "example.application/.MainActivity",
                "example.application/.MainActivity",
                "freeform",
                new Rect(0, 0, 800, 600),
                FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD,
                densityDpi,
                false,
                true,
                true,
                PROFILE.userId);
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
