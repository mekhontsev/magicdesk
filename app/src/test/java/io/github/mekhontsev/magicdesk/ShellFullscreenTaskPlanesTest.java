package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ShellFullscreenTaskPlanesTest {
    @Test
    public void createsAnchorBeforePublishingAnOrganizerPlane() throws Exception {
        final String source = RuntimeSourceFixture.methods("ShellFullscreenTaskPlanes", "acquirePlane");
        final int launch = source.indexOf(".launchFullscreenTaskBehind(");
        final int create = source.indexOf("TaskDisplayAreaHandle.createWithSurface(");
        final int populate = source.indexOf("windowing.reparent(populate, anchorToken, plane.token()");
        final int commit = source.indexOf("windowing.transactionClass(), populate)");
        final int publish = source.indexOf("mLaunchGuard.add(");
        org.junit.Assert.assertTrue(launch >= 0 && launch < create && create < populate
                && populate < commit && commit < publish);
    }

    @Test
    public void staleAnchorFocusCannotUndoALaterApplicationSelection() throws Exception {
        verifyAnchorRecovery("""
                snapshot = new FrameworkTaskSnapshot(false);
                check(recoverAnchorFocus(new Object(), 10, null), "owned anchor not recognized");
                check(recoveries == 0, "stale callback changed selection");
                snapshot = null;
                check(recoverAnchorFocus(new Object(), 10, null), "removed anchor not recognized");
                check(recoveries == 0, "removed anchor changed selection");
                """);
    }

    @Test
    public void currentAnchorFocusStillRecoversThroughThePlaneOwner() throws Exception {
        verifyAnchorRecovery("""
                snapshot = new FrameworkTaskSnapshot(true);
                check(recoverAnchorFocus(new Object(), 10, null), "owned anchor not recognized");
                check(recoveries == 1, "current anchor did not recover");
                check(!recoverAnchorFocus(new Object(), 11, null), "unowned anchor accepted");
                check(recoveries == 1, "unowned anchor changed selection");
                """);
    }

    private static void verifyAnchorRecovery(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final String TAG = "test";
                static int mDisplayId = 4, recoveries;
                static FrameworkTaskSnapshot snapshot;
                static class TaskDisplayAreaHandle {}
                static class ShellDesktopTaskOwnership {}
                static class FrameworkTaskSnapshot {
                    final boolean focused;
                    FrameworkTaskSnapshot(boolean focused) { this.focused = focused; }
                }
                static class FrameworkTaskSnapshotSource {
                    static FrameworkTaskSnapshot findTask(Object service, int displayId, int taskId)
                            throws ReflectiveOperationException {
                        check(displayId == 4 && taskId == 10, "wrong observation identity");
                        return snapshot;
                    }
                }
                static class Log {
                    static void w(String tag, String message) {}
                    static void w(String tag, String message, Throwable error) {
                        throw new AssertionError(error);
                    }
                }
                TaskDisplayAreaHandle findAnchorPlane(int taskId) {
                    return taskId == 10 ? new TaskDisplayAreaHandle() : null;
                }
                int findPlaneTaskId(TaskDisplayAreaHandle plane) { return 20; }
                int findRemovalFocusTarget(Object service, int display, int task,
                        ShellDesktopTaskOwnership ownership) { return 30; }
                void applyPlaneExitFocus(Object service, int display,
                        TaskDisplayAreaHandle plane, int successor) {
                    check(successor == 30, "wrong successor"); recoveries++;
                }
                public static void verify() throws Exception { new Fixture().scenario(); }
                void scenario() throws Exception {
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                        "ShellFullscreenTaskPlanes", "recoverAnchorFocus"));
    }

    @Test
    public void closingForemostPlaneRecoversPrematureHomeSelection() {
        assertEquals(true, ShellFullscreenTaskPlanes.shouldRecoverRemovalFromHome(
                11, 99, 4, Map.of(10, 1, 11, 2), planeIds(10, 11), false,
                Arrays.asList(task(99, 1), task(10, 1))));
    }

    @Test
    public void closingBackgroundPlaneCannotReclaimFocus() {
        assertEquals(false, ShellFullscreenTaskPlanes.shouldRecoverRemovalFromHome(
                10, 99, 4, Map.of(10, 1, 11, 2), planeIds(10, 11), false,
                Arrays.asList(task(99, 1), task(11, 1))));
    }

    @Test
    public void removalKeepsAnAlreadyExposedApplication() {
        for (final int mode : new int[]{1, 5}) {
            assertEquals(false, ShellFullscreenTaskPlanes.shouldRecoverRemovalFromHome(
                    11, 99, 4, Map.of(10, 1, 11, 2), planeIds(10, 11), false,
                    Arrays.asList(task(20, mode), task(99, 1), task(10, 1))));
        }
    }

    @Test
    public void removalDoesNotUndoExplicitDesktopPresentation() {
        assertEquals(false, ShellFullscreenTaskPlanes.shouldRecoverRemovalFromHome(
                11, 99, 4, Map.of(10, 1, 11, 2), planeIds(10, 11), true,
                Arrays.asList(task(99, 1), task(10, 1))));
        assertEquals(false, ShellFullscreenTaskPlanes.shouldRecoverRemovalFromHome(
                11, 99, 4, Map.of(10, -2, 11, -1), planeIds(10, 11), false,
                Arrays.asList(task(99, 1), task(10, 1))));
    }

    @Test
    public void removalRequiresKnownCompositionAndCurrentHome() {
        assertEquals(false, ShellFullscreenTaskPlanes.shouldRecoverRemovalFromHome(
                11, 99, 4, Map.of(), planeIds(10, 11), false,
                Arrays.asList(task(99, 1), task(10, 1))));
        assertEquals(false, ShellFullscreenTaskPlanes.shouldRecoverRemovalFromHome(
                11, 99, 4, Map.of(11, 2), planeIds(10), false,
                Arrays.asList(task(99, 1), task(10, 1))));
        assertEquals(false, ShellFullscreenTaskPlanes.shouldRecoverRemovalFromHome(
                11, 99, 4, Map.of(11, 2), planeIds(11), false, Collections.emptyList()));
        assertEquals(false, ShellFullscreenTaskPlanes.shouldRecoverRemovalFromHome(
                11, 99, 0, Map.of(11, 2), planeIds(11), false,
                Arrays.asList(task(99, 1), task(10, 1))));
    }

    @Test
    public void removalIgnoresRetiredLayersAndItsOwnClosingSurface() {
        assertEquals(true, ShellFullscreenTaskPlanes.shouldRecoverRemovalFromHome(
                11, 99, 4, Map.of(10, 1, 11, 2, 12, 3), planeIds(10, 11), false,
                Arrays.asList(task(11, 1), task(99, 1), task(10, 1))));
    }

    @Test
    public void freeformRestoreRetainsComposedFullscreenBackgroundBelowHomeInHierarchy() {
        assertEquals(11, ShellFullscreenTaskPlanes.composedFullscreenBackground(
                -1, Arrays.asList(10, 11), false));
    }

    @Test
    public void explicitDesktopPresentationDoesNotRestoreCoveredPlanes() {
        assertEquals(-1, ShellFullscreenTaskPlanes.composedFullscreenBackground(
                -1, Arrays.asList(10, 11), true));
    }

    @Test
    public void foregroundFullscreenHierarchyTakesPrecedenceOverPreviousComposition() {
        assertEquals(12, ShellFullscreenTaskPlanes.composedFullscreenBackground(
                12, Arrays.asList(10, 11), true));
    }

    @Test
    public void lastPlaneReleaseReturnsToDesktopBackground() {
        assertEquals(-1, ShellFullscreenTaskPlanes.composedFullscreenBackground(
                -1, Collections.emptyList(), false));
    }

    @Test
    public void appendsRequestedStackAboveEveryFullscreenPlane() {
        assertArrayEquals(
                new int[]{1, 2, 3},
                ShellFullscreenTaskPlanes.completeStableOrder(
                        Arrays.asList(1, 2),
                        new int[]{3},
                        planeIds(1, 2)));
    }

    @Test
    public void movesRequestedFullscreenTaskWithoutDuplicatingIt() {
        assertArrayEquals(
                new int[]{1, 3, 2},
                ShellFullscreenTaskPlanes.completeStableOrder(
                        Arrays.asList(1, 2, 3),
                        new int[]{2},
                        planeIds(1, 2, 3)));
    }

    @Test
    public void preservesExplicitDemotionOrder() {
        assertArrayEquals(
                new int[]{3, 1, 2, 4},
                ShellFullscreenTaskPlanes.completeStableOrder(
                        Arrays.asList(3, 1, 2),
                        new int[]{2, 1, 4},
                        planeIds(1, 2, 3)));
    }

    @Test
    public void chainsRapidFocusChangesFromCommittedPlaneOrder() {
        final Set<Integer> planes = planeIds(1, 2, 3);
        final int[] first = ShellFullscreenTaskPlanes.completeStableOrder(
                Arrays.asList(1, 2, 3), new int[]{2, 3, 1}, planes);
        final int[] second = ShellFullscreenTaskPlanes.completeStableOrder(
                asList(first), new int[]{3, 1, 2}, planes);
        final int[] third = ShellFullscreenTaskPlanes.completeStableOrder(
                asList(second), new int[]{2, 1, 3}, planes);

        assertArrayEquals(new int[]{2, 3, 1}, first);
        assertArrayEquals(new int[]{3, 1, 2}, second);
        assertArrayEquals(new int[]{1, 2, 3}, third);
    }

    @Test
    public void ordersPlaneSurfacesAroundOrdinaryWorkspaceTasks() {
        final Map<Integer, Integer> expected = new LinkedHashMap<>();
        expected.put(Integer.valueOf(10), Integer.valueOf(1));
        expected.put(Integer.valueOf(11), Integer.valueOf(3));

        assertEquals(
                expected,
                ShellFullscreenTaskPlanes.surfaceLayers(
                        new int[]{10, 20, 11},
                        planeIds(10, 11),
                        false));
    }

    @Test
    public void keepsPlaneSurfacesOrderedBelowDesktopWorkspace() {
        final Map<Integer, Integer> expected = new LinkedHashMap<>();
        expected.put(Integer.valueOf(10), Integer.valueOf(-2));
        expected.put(Integer.valueOf(11), Integer.valueOf(-1));

        assertEquals(
                expected,
                ShellFullscreenTaskPlanes.surfaceLayers(
                        new int[]{10, 11},
                        planeIds(10, 11),
                        true));
    }

    @Test
    public void lowersPlanesWithoutReversingTheirMixedStackOrder() {
        assertArrayEquals(
                new int[]{11, 10},
                ShellFullscreenTaskPlanes.planeBottomReorderOrder(
                        new int[]{10, 11, 20},
                        planeIds(10, 11)));
    }

    @Test
    public void ignoresOrdinaryTasksWhenLoweringFullscreenPlanes() {
        assertArrayEquals(
                new int[]{12, 11, 10},
                ShellFullscreenTaskPlanes.planeBottomReorderOrder(
                        new int[]{10, 20, 11, 21, 12, 22},
                        planeIds(10, 11, 12)));
    }

    @Test
    public void lowersOnlyCoveredPlanesWhenSelectingFullscreenPeer() {
        assertArrayEquals(
                new int[]{11, 10},
                ShellFullscreenTaskPlanes.coveredPlaneBottomReorderOrder(
                        new int[]{10, 11, 99, 12},
                        planeIds(10, 11, 12),
                        12));
    }

    @Test
    public void selectsFullscreenChildWhenFocusCrossesWorkspaceBoundary() {
        assertEquals(
                true,
                ShellFullscreenTaskPlanes.crossesFullscreenPlaneBoundary(
                        new int[]{20, 99, 10, 11},
                        planeIds(10, 11)));
    }

    @Test
    public void leavesFullscreenPlaneSwitchChildLifecycleUntouched() {
        assertEquals(
                false,
                ShellFullscreenTaskPlanes.crossesFullscreenPlaneBoundary(
                        new int[]{10, 11},
                        planeIds(10, 11)));
    }

    @Test
    public void activatingCoveredFreeformDoesNotRaiseItsCoveredPeer() {
        // Firefox covers both Files and Golly even when all three report visible.
        final ShellFullscreenTaskPlanes.ForegroundWorkspace foreground =
                ShellFullscreenTaskPlanes.foregroundWorkspace(Arrays.asList(
                        task(10, 1), task(21, 5), task(20, 5), task(99, 1)), 99);
        assertEquals(10, foreground.fullscreenTaskId);
        assertEquals(Collections.emptyList(), foreground.freeformTaskIds);
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        20, 99, new int[]{20}, planeIds(10),
                        foreground.freeformTaskIds, foreground.fullscreenTaskId, true);
        assertArrayEquals(new int[]{20}, order.freeformTaskIds);
        assertEquals(10, order.fullscreenTaskId);
    }

    @Test
    public void activationKeepsExposedPeersWithoutRestoringCoveredPeers() {
        final ShellFullscreenTaskPlanes.ForegroundWorkspace foreground =
                ShellFullscreenTaskPlanes.foregroundWorkspace(Arrays.asList(
                        task(23, 5), task(22, 5), task(10, 1), task(21, 5),
                        task(20, 5), task(99, 1)), 99);
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        20, 99, new int[]{20}, planeIds(10),
                        foreground.freeformTaskIds, foreground.fullscreenTaskId, true);
        assertArrayEquals(new int[]{22, 23, 20}, order.freeformTaskIds);
    }

    @Test
    public void activationFromHomeDoesNotResurrectFullscreenBackground() {
        final ShellFullscreenTaskPlanes.ForegroundWorkspace foreground =
                ShellFullscreenTaskPlanes.foregroundWorkspace(Arrays.asList(
                        task(99, 1), task(10, 1), task(21, 5), task(20, 5)), 99);
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        20, 99, new int[]{20}, planeIds(10),
                        foreground.freeformTaskIds, foreground.fullscreenTaskId, true);
        assertNotNull(order);
        assertEquals(-1, order.fullscreenTaskId);
        assertArrayEquals(new int[]{20}, order.freeformTaskIds);
    }

    @Test
    public void explicitRestoreCanRecoverCoveredPeersAndFullscreenBackground() {
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        20, 99, new int[]{10, 21, 20}, planeIds(10),
                        Collections.emptyList(), -1, true);
        assertArrayEquals(new int[]{21, 20}, order.freeformTaskIds);
        assertEquals(10, order.fullscreenTaskId);
    }

    @Test
    public void explicitRestoreOrdersItsFullscreenBackgroundInHierarchyAndSurfaces() {
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        20, 99, new int[]{10, 21, 20}, planeIds(10, 11),
                        Collections.emptyList(), -1, true);
        assertArrayEquals(new int[]{11, 10, 21, 20},
                ShellFullscreenTaskPlanes.mixedSurfaceOrder(
                        new int[]{10, 11, 21, 20}, order));
    }

    @Test
    public void presentWorkspaceKeepsFullscreenBelowHome() {
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        20, 99, new int[]{10, 99, 21, 20}, planeIds(10),
                        Arrays.asList(22), 10, true);
        assertEquals(-1, order.fullscreenTaskId);
        assertArrayEquals(new int[]{21, 20}, order.freeformTaskIds);
    }

    @Test
    public void demotionDoesNotReintroduceTheConcealedWindow() {
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        21, 99, new int[]{20, 99, 10, 21}, planeIds(10),
                        Arrays.asList(21, 20), 10, true);
        assertArrayEquals(new int[]{21}, order.freeformTaskIds);
        assertEquals(10, order.fullscreenTaskId);
    }

    @Test
    public void raisesWorkspaceForSelectedFreeformTask() {
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        20,
                        99,
                        new int[]{20},
                        planeIds(10, 11),
                        Arrays.asList(20),
                        11,
                        true);
        assertNotNull(order);
        assertEquals(20, order.targetTaskId);
        assertEquals(99, order.desktopHostTaskId);
        assertEquals(11, order.fullscreenTaskId);
        assertArrayEquals(new int[]{20}, order.freeformTaskIds);
        assertEquals(false, order.fullscreenForeground);
    }

    @Test
    public void raisesSelectedFullscreenAboveVisibleFreeformWorkspace() {
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        11,
                        99,
                        new int[]{10, 11},
                        planeIds(10, 11),
                        Arrays.asList(20),
                        10,
                        false);
        assertNotNull(order);
        assertEquals(11, order.targetTaskId);
        assertEquals(11, order.fullscreenTaskId);
        assertArrayEquals(new int[]{20}, order.freeformTaskIds);
        assertEquals(true, order.fullscreenForeground);
    }

    @Test
    public void lowersDemotedFreeformWorkspaceBelowFullscreenPeer() {
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                11,
                99,
                new int[]{20, 99, 11},
                planeIds(10, 11),
                Arrays.asList(20),
                10,
                false);
        assertNotNull(order);
        assertArrayEquals(new int[]{20}, order.freeformTaskIds);
        assertEquals(true, order.fullscreenForeground);
    }

    @Test
    public void movesExplicitFreeformBlockersBelowFullscreenPeer() {
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        11,
                        99,
                        new int[]{20, 99, 21, 11},
                        planeIds(10, 11),
                        Arrays.asList(20, 21),
                        10,
                        false);
        assertNotNull(order);
        assertArrayEquals(new int[]{20, 21}, order.freeformTaskIds);
        assertEquals(true, order.fullscreenForeground);
    }

    @Test
    public void demotionRetainsCoveredBlockerBeforeActivatingAnotherFreeform() {
        // Demoting Golly must lower its root, not only raise Firefox's plane.
        // Files is selected next and must not expose that old Golly surface.
        final ShellFullscreenTaskPlanes.MixedStackOrder demotion =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        10, 99, new int[]{20, 99, 10}, planeIds(10),
                        Collections.emptyList(), 10, false);
        assertNotNull(demotion);
        assertArrayEquals(new int[]{20}, demotion.freeformTaskIds);
        assertEquals(true, demotion.fullscreenForeground);

        final ShellFullscreenTaskPlanes.ForegroundWorkspace foreground =
                ShellFullscreenTaskPlanes.foregroundWorkspace(Arrays.asList(
                        task(10, 1), task(99, 1), task(20, 5), task(21, 5)), 99);
        final ShellFullscreenTaskPlanes.MixedStackOrder activation =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        21, 99, new int[]{21}, planeIds(10),
                        foreground.freeformTaskIds, foreground.fullscreenTaskId, true);
        assertArrayEquals(new int[]{21}, activation.freeformTaskIds);
        assertEquals(10, activation.fullscreenTaskId);
    }

    @Test
    public void movesOmittedVisibleFreeformBlockerBelowFullscreenPeer() {
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        11,
                        99,
                        new int[]{10, 99, 11},
                        planeIds(10, 11),
                        Arrays.asList(20),
                        10,
                        false);
        assertNotNull(order);
        assertArrayEquals(new int[]{20}, order.freeformTaskIds);
        assertEquals(true, order.fullscreenForeground);
    }

    @Test
    public void layersFreeformWorkspaceBelowSelectedFullscreenPlane() {
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        11,
                        99,
                        new int[]{11},
                        planeIds(10, 11),
                        Arrays.asList(20),
                        10,
                        false);

        assertArrayEquals(
                new int[]{10, 20, 11},
                ShellFullscreenTaskPlanes.mixedSurfaceOrder(
                        new int[]{10, 11}, order));
    }

    @Test
    public void layersSelectedFreeformWorkspaceAboveFullscreenPlanes() {
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        20,
                        99,
                        new int[]{20},
                        planeIds(10, 11),
                        Arrays.asList(20),
                        11,
                        true);

        assertArrayEquals(
                new int[]{10, 11, 20},
                ShellFullscreenTaskPlanes.mixedSurfaceOrder(
                        new int[]{10, 11, 20}, order));
        assertEquals(true, order.selectsFreeformTask(20));
        assertEquals(false, order.selectsFreeformTask(19));
    }

    @Test
    public void fullscreenMixedOrderDoesNotSelectAFreeformParent() {
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        11,
                        99,
                        new int[]{20, 11},
                        planeIds(10, 11),
                        Arrays.asList(20),
                        10,
                        false);

        assertEquals(false, order.selectsFreeformTask(20));
    }

    private static FrameworkTaskSnapshot task(final int taskId, final int mode) {
        return new FrameworkTaskSnapshot(null, taskId, taskId, 4, 1, mode,
                FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD,
                null, null, "", "", "", "", -1, "", null, true, false, null);
    }

    private static java.util.List<Integer> asList(final int[] values) {
        final java.util.List<Integer> result = new java.util.ArrayList<>();
        for (final int value : values) {
            result.add(Integer.valueOf(value));
        }
        return result;
    }

    private static Set<Integer> planeIds(final Integer... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
