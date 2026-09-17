package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;

public final class DesktopDisplayIdentityTest {
    @Test public void onlyKnownPublicUntrustedOutputsRequestPortableDesktop() {
        for (String source : new String[]{"wired", "wireless", "virtual", "overlay"}) {
            assertTrue(DesktopDisplayInfo.requiresPortableDesktop(7, source, true, false));
            assertFalse(DesktopDisplayInfo.requiresPortableDesktop(7, source, true, true));
            assertFalse(DesktopDisplayInfo.requiresPortableDesktop(7, source, false, false));
        }
        assertFalse(DesktopDisplayInfo.requiresPortableDesktop(0, "phone", true, false));
        assertFalse(DesktopDisplayInfo.requiresPortableDesktop(1, "internal", true, false));
        assertFalse(DesktopDisplayInfo.requiresPortableDesktop(7, "unknown", true, false));
    }

    private static DesktopDisplayInfo display(final int id, final String source, final boolean owned) {
        return new DesktopDisplayInfo(id, "id:" + id, source, source, source, 1280, 720, 160, false, false, owned, false);
    }

    @Test public void protectedSourcePolicyIsDistinctFromOutputSecurityAndDesktopEligibility() {
        final DesktopDisplayInfo protectedSource = new DesktopDisplayInfo(7, "source", "Source", "Source", "virtual",
                1280, 720, 160, true, false, true, true);
        final DesktopDisplayInfo secureOutput = new DesktopDisplayInfo(8, "output", "Output", "Output", "wireless",
                1280, 720, 160, false, true, false, true);
        assertTrue(protectedSource.protectedContent());
        assertFalse(secureOutput.protectedContent());
        protectedSource.requirePresentationOutput(secureOutput);
        assertThrows(IllegalArgumentException.class,
                () -> protectedSource.requirePresentationOutput(display(9, "wired", false)));
        display(10, "virtual", true).requirePresentationOutput(display(11, "wireless", false));
        secureOutput.requirePresentationOutput(display(11, "wireless", false));
        assertFalse(new DesktopDisplayInfo(12, "foreign", "Foreign", "Foreign", "virtual",
                1280, 720, 160, true, false, false, true).protectedContent());
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
