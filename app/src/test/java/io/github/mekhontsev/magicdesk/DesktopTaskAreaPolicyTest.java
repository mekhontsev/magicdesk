package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopTaskAreaPolicyTest {
    @Test
    public void fullscreenHierarchyFollowsTaskAreaOwnership() {
        assertFalse(DesktopTaskAreaPolicy.UNCONFIGURED
                .usesSessionFullscreenHierarchy());
        assertTrue(DesktopTaskAreaPolicy.SESSION
                .usesSessionFullscreenHierarchy());
        assertFalse(DesktopTaskAreaPolicy.UNCONFIGURED
                .usesManagedApplicationArea());
        assertTrue(DesktopTaskAreaPolicy.SESSION
                .usesManagedApplicationArea());
        assertFalse(DesktopTaskAreaPolicy.INDEPENDENT
                .usesManagedApplicationArea());
        assertFalse(DesktopTaskAreaPolicy.UNCONFIGURED.usesDirectRootWorkspace());
        assertFalse(DesktopTaskAreaPolicy.SESSION.usesDirectRootWorkspace());
        assertTrue(DesktopTaskAreaPolicy.INDEPENDENT
                .usesDirectRootWorkspace());
        assertFalse(DesktopTaskAreaPolicy.UNCONFIGURED
                .usesIndependentFullscreenPlanes());
        assertFalse(DesktopTaskAreaPolicy.SESSION
                .usesIndependentFullscreenPlanes());
        assertTrue(DesktopTaskAreaPolicy.INDEPENDENT
                .usesIndependentFullscreenPlanes());
        assertEquals(DesktopTaskAreaPolicy.INDEPENDENT,
                DesktopTaskAreaPolicy.fromWireValue(
                        DesktopTaskAreaPolicy.INDEPENDENT.wireValue()));
    }
}
