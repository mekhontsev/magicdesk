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
}
