package io.github.mekhontsev.magicdesk;

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
    }
}
