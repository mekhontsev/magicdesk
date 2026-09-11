package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;

public final class DesktopDisplayIdentityTest {
    private static DesktopDisplayInfo display(final int id, final String source, final boolean owned) {
        return new DesktopDisplayInfo(id, "id:" + id, source, source, 1280, 720, 160, false, owned);
    }

    @Test public void builtInDoesNotMeanDefaultAndNonDefaultDoesNotMeanExternal() {
        assertTrue(display(0, "phone", false).isDefaultDisplay());
        assertTrue(display(0, "phone", false).isBuiltIn());
        for (int id : new int[] {1, 2}) {
            assertFalse(display(id, "internal", false).isDefaultDisplay());
            assertTrue(display(id, "internal", false).isBuiltIn());
        }
        assertFalse(display(7, "wired", false).isBuiltIn());
    }

    @Test public void ownershipCannotAuthorizeDeletingBuiltInDisplays() {
        assertFalse(display(0, "phone", true).canRemove());
        assertFalse(display(1, "internal", true).canRemove());
        assertFalse(display(7, "virtual", false).canRemove());
        assertTrue(display(7, "virtual", true).canRemove());
    }

    @Test public void catalogAdmissionPreservesCurrentSupportedPaths() {
        assertTrue(DesktopDisplayInfo.supportsDesktop(0, "phone", false, false));
        assertFalse(DesktopDisplayInfo.supportsDesktop(1, "internal", true, true));
        assertFalse(DesktopDisplayInfo.supportsDesktop(2, "internal", true, true));
        assertFalse(DesktopDisplayInfo.supportsDesktop(7, "unknown", true, true));
        for (String source : new String[] {"wired", "wireless", "virtual", "overlay"}) {
            assertTrue(DesktopDisplayInfo.supportsDesktop(7, source, true, true));
            assertFalse(DesktopDisplayInfo.supportsDesktop(7, source, false, true));
            assertFalse(DesktopDisplayInfo.supportsDesktop(7, source, true, false));
        }
    }
}
