package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ShellFullscreenLaunchGuardTest {
    private static final List<ShellFullscreenLaunchGuard.Boundary> BOUNDARIES = List.of(
            new ShellFullscreenLaunchGuard.Boundary(100, "plane-a", 10, "separator-a"),
            new ShellFullscreenLaunchGuard.Boundary(101, "plane-b", 11, "separator-b"));

    @Test
    public void failedWorkspaceCannotVetoOtherDisplaysOrUnscopedLaunches() throws Exception {
        RuntimeSourceFixture.verify("""
                record Intent() { }
                record LaunchActivityIdentity(String pkg) {
                    boolean matchesStart(Intent intent, String name) { return intent != null && pkg.equals(name); }
                }
                """ + RuntimeSourceFixture.nestedClass("ShellActivityLaunchScope", "ShellActivityLaunchScope")
                        .replace("final class ShellActivityLaunchScope", "static final class ShellActivityLaunchScope")
                + """
                static class ShellActivityStartController {
                    interface Listener { boolean onActivityStarting(Intent intent, String name); }
                    boolean mEnabled = true;
                    Listener[] mListeners;
                    final List<String> errors = new ArrayList<>();
                    void report(String error) { errors.add(error); }
                    void notifyActivityStart(Intent intent, String name, boolean allowed) { }
                """ + RuntimeSourceFixture.nestedClass("ShellActivityStartController", "StartRejectedException")
                + RuntimeSourceFixture.methods("ShellActivityStartController", "allowActivityStart", "usefulMessage")
                + """
                }
                static class Guard implements ShellActivityStartController.Listener {
                    record State(int displayId) { }
                    State mState = new State(5);
                    Throwable failure;
                    void protect(State state) throws ReflectiveOperationException {
                        if (failure instanceof ReflectiveOperationException error) throw error;
                        if (failure instanceof RuntimeException error) throw error;
                    }
                """ + RuntimeSourceFixture.methods("ShellFullscreenLaunchGuard", "onActivityStarting")
                + """
                }
                public static void verify() {
                    Guard guard = new Guard();
                    ShellActivityStartController controller = new ShellActivityStartController();
                    controller.mListeners = new ShellActivityStartController.Listener[] {guard};
                    Intent intent = new Intent();
                    LaunchActivityIdentity identity = new LaunchActivityIdentity("app");
                    check(controller.allowActivityStart(intent, "app"), "healthy launch rejected");
                    for (Throwable error : List.of(new IllegalStateException("separator missing"),
                            new ReflectiveOperationException("snapshot unavailable"))) {
                        guard.failure = error;
                        check(controller.allowActivityStart(intent, "app"), "native phone launch blocked");
                        try (var phone = ShellActivityLaunchScope.begin(identity, 0)) {
                            check(controller.allowActivityStart(intent, "app"), "explicit phone launch blocked");
                        }
                        try (var other = ShellActivityLaunchScope.begin(identity, 7)) {
                            check(controller.allowActivityStart(intent, "app"), "another Desktop launch blocked");
                        }
                        try (var desktop = ShellActivityLaunchScope.begin(identity, 5)) {
                            check(!controller.allowActivityStart(intent, "app"), "unsafe explicit launch accepted");
                            check(controller.allowActivityStart(intent, "settings"), "unrelated diagnostics blocked");
                        }
                        check(controller.allowActivityStart(intent, "app"), "closed scope still rejects native launch");
                        check(controller.errors.stream().allMatch(message -> message.contains("display=5")
                                && message.contains("package=") && message.contains(error.getMessage())),
                                "repair failure lost its context or root cause");
                        check(controller.errors.stream().filter(message -> message.startsWith("activity-start rejected:")).count() == 1,
                                "veto must be limited to the affected explicit destination");
                        controller.errors.clear();
                    }
                }
                """);
    }

    @Test
    public void restoresBothBoundariesWithoutChangingApplicationOrder() {
        assertEquals(List.of(3, -101, 11, 2, -100, 10, 1), repair(3, -101, 2, -100, 1, 10, 11));
    }

    @Test
    public void doesNotRaiseFullscreenPlanesAboveAnIndependentApplication() {
        assertEquals(List.of(1, 2, -100, 10, -101, 11), repair(1, 2, 10, 11, -100, -101));
    }

    @Test
    public void homeAndAnchorInsideAPlaneAreOneSibling() {
        final List<FrameworkTaskSnapshot> roots = new ArrayList<>(roots(1, -100, 2, 10));
        roots.add(2, root(500, 100));
        assertEquals(List.of(1, -100, 10, 2),
                ShellFullscreenLaunchGuard.repairOrder(roots, BOUNDARIES));
    }

    @Test
    public void alreadyAdjacentHasNoTransaction() {
        assertTrue(repair(1, -100, 10, 2, -101, 11, 3).isEmpty());
    }

    @Test
    public void absentAreaDoesNotMoveItsRetainedSeparator() {
        assertTrue(repair(11, 1, -100, 10, 2).isEmpty());
    }

    @Test
    public void missingOrMisplacedSeparatorCannotProduceAPartialRepair() {
        assertThrows(IllegalStateException.class, () -> repair(1, -100));
        assertThrows(IllegalStateException.class, () ->
                ShellFullscreenLaunchGuard.repairOrder(
                        List.of(root(1, 100), root(10, 100)), BOUNDARIES));
    }

    @Test
    public void chromeOutsideTheWorkspaceIsNotReordered() {
        final List<FrameworkTaskSnapshot> roots = new ArrayList<>(roots(-100, 1, 10));
        roots.add(0, root(500, 999));
        assertEquals(List.of(-100, 10, 1),
                ShellFullscreenLaunchGuard.repairOrder(roots, BOUNDARIES));
    }

    @Test
    public void allSiblingPermutationsPreserveApplicationOrderAndAreIdempotent() {
        permutations(new ArrayList<>(List.of(1, 2, -100, -101, 10, 11)), 0);
    }

    private static void permutations(final List<Integer> order, final int index) {
        if (index == order.size()) {
            final List<Integer> repaired = repair(order.toArray(Integer[]::new));
            final List<Integer> effective = repaired.isEmpty() ? order : repaired;
            assertEquals(applications(order), applications(effective));
            assertEquals(Integer.valueOf(10), effective.get(effective.indexOf(-100) + 1));
            assertEquals(Integer.valueOf(11), effective.get(effective.indexOf(-101) + 1));
            assertTrue(repair(effective.toArray(Integer[]::new)).isEmpty());
            return;
        }
        for (int i = index; i < order.size(); i++) {
            Collections.swap(order, index, i);
            permutations(order, index + 1);
            Collections.swap(order, index, i);
        }
    }

    private static List<Integer> applications(final List<Integer> order) {
        return order.stream().filter(id -> id != 10 && id != 11).toList();
    }

    private static List<Integer> repair(final Integer... ids) {
        return ShellFullscreenLaunchGuard.repairOrder(roots(ids), BOUNDARIES);
    }

    private static List<FrameworkTaskSnapshot> roots(final Integer... ids) {
        final List<FrameworkTaskSnapshot> result = new ArrayList<>();
        for (final int id : ids) { result.add(root(id < 0 ? -id + 1000 : id, id < 0 ? -id : 1)); }
        return result;
    }

    private static FrameworkTaskSnapshot root(final int id, final int area) {
        return new FrameworkTaskSnapshot(null, id, id, 4, area, 1,
                FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD,
                null, null, "", "", "", "", -1, "", null, true, false, null);
    }
}
