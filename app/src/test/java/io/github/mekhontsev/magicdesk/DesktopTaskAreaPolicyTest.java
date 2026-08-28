package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopTaskAreaPolicyTest {
    @Test
    public void fullscreenHierarchyFollowsTaskAreaOwnership() {
        assertFalse(DesktopTaskAreaPolicy.DEFAULT
                .usesSessionFullscreenHierarchy());
        assertTrue(DesktopTaskAreaPolicy.SESSION
                .usesSessionFullscreenHierarchy());
        assertFalse(DesktopTaskAreaPolicy.DEFAULT.usesSessionParent());
        assertTrue(DesktopTaskAreaPolicy.SESSION.usesSessionParent());
        assertFalse(DesktopTaskAreaPolicy.INDEPENDENT.usesSessionParent());
        assertFalse(DesktopTaskAreaPolicy.DEFAULT.usesManagedWorkspaceArea());
        assertTrue(DesktopTaskAreaPolicy.SESSION.usesManagedWorkspaceArea());
        assertTrue(DesktopTaskAreaPolicy.INDEPENDENT
                .usesManagedWorkspaceArea());
        assertTrue(DesktopTaskAreaPolicy.DEFAULT
                .usesIndependentFullscreenPlanes());
        assertFalse(DesktopTaskAreaPolicy.SESSION
                .usesIndependentFullscreenPlanes());
        assertTrue(DesktopTaskAreaPolicy.INDEPENDENT
                .usesIndependentFullscreenPlanes());
        assertEquals(2, DesktopTaskAreaPolicy.DEFAULT
                .minimumFullscreenTasksForSharedArea());
        assertEquals(2, DesktopTaskAreaPolicy.SESSION
                .minimumFullscreenTasksForSharedArea());
        assertFalse(DesktopTaskAreaPolicy.DEFAULT
                .requiresFullscreenBackstop());
        assertTrue(DesktopTaskAreaPolicy.SESSION
                .requiresFullscreenBackstop());
        assertEquals(DesktopTaskAreaPolicy.INDEPENDENT,
                DesktopTaskAreaPolicy.fromWireValue(
                        DesktopTaskAreaPolicy.INDEPENDENT.wireValue()));
    }
}
