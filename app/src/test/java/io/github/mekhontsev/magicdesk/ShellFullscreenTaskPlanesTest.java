package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ShellFullscreenTaskPlanesTest {
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
    public void surfaceOrderContainsOnlyFullscreenPlanes() {
        assertArrayEquals(
                new int[]{10, 11},
                ShellFullscreenTaskPlanes.planeOnlyOrder(
                        new int[]{20, 10, 21, 11, 22},
                        planeIds(10, 11)));
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
        assertNull(ShellFullscreenTaskPlanes.buildMixedStackOrder(
                11,
                99,
                new int[]{20, 99, 11},
                planeIds(10, 11),
                Arrays.asList(20),
                10,
                false));
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
        assertArrayEquals(new int[]{21}, order.freeformTaskIds);
        assertEquals(true, order.fullscreenForeground);
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
