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
        assertTrue(DesktopTaskAreaPolicy.DEFAULT
                .usesIndependentFullscreenPlanes());
        assertFalse(DesktopTaskAreaPolicy.SESSION
                .usesIndependentFullscreenPlanes());
        assertEquals(2, DesktopTaskAreaPolicy.DEFAULT
                .minimumFullscreenTasksForSharedArea());
        assertEquals(2, DesktopTaskAreaPolicy.SESSION
                .minimumFullscreenTasksForSharedArea());
        assertFalse(DesktopTaskAreaPolicy.DEFAULT
                .requiresFullscreenBackstop());
        assertTrue(DesktopTaskAreaPolicy.SESSION
                .requiresFullscreenBackstop());
    }
}
