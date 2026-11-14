package io.github.mekhontsev.magicdesk;

import java.util.Collections;

import org.junit.Test;

public final class ShellDesktopSurfaceOrderTest {
    @Test
    public void freeformOnlyWorkspaceNeedsNoSurfaceTransaction() throws Exception {
        final ShellDesktopSurfaceOrder order = new ShellDesktopSurfaceOrder();

        // No fullscreen plane means no SurfaceControl access, even without an
        // Android runtime. Chrome is ordered independently by WindowManager.
        order.applyLayers(Collections.emptyMap());
        order.setVisible(Collections.emptyList(), true);
        order.setVisible(Collections.emptyList(), false);
    }

    @Test
    public void independentHostCountsNativeSiblingsNotPlaneChildren() throws Exception {
        RuntimeSourceFixture.verify("""
                record FrameworkTaskSnapshot(int taskId, int displayAreaFeatureId) { }
                static class TaskDisplayAreaHandle {
                    enum Parent { DEFAULT_TASK_CONTAINER; int featureId() { return 1; } }
                }
                static FrameworkTaskSnapshot root(int id, int area) {
                    return new FrameworkTaskSnapshot(id, area);
                }
                public static void verify() {
                    var areas = java.util.Set.of(101, 102, 103);
                    var roots = java.util.List.of(root(99, 200), root(10, 1),
                            root(21, 102), root(22, 102), root(23, 1), root(30, 1),
                            root(41, 101), root(42, 101), root(43, 1),
                            root(51, 103), root(53, 1));
                    check(independentHostLayerBase(roots, areas, 30) == 4,
                            "host base must include separators and collapse plane children");
                    check(independentHostLayerBase(java.util.List.of(root(30, 1)), areas, 30) == 0,
                            "empty workspace has a zero base");
                    try {
                        independentHostLayerBase(roots, areas, 100);
                        throw new AssertionError("missing host silently used HOME layer zero");
                    } catch (IllegalStateException expected) { }
                }
                """ + RuntimeSourceFixture.methods("ShellDesktopSurfaceOrder", "independentHostLayerBase"));
    }
}
