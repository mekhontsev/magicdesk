package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class VirtualDisplaySpecTest {
    @Test public void unlockedPolicyIsExplicitIndependentAndRejectsOverlay() {
        final VirtualDisplaySpec ordinary = new VirtualDisplaySpec(1280, 720, 160);
        assertFalse(ordinary.alwaysUnlocked);
        final VirtualDisplaySpec unlocked = ordinary.withAlwaysUnlocked(true).withOrigin("source");
        assertTrue(unlocked.alwaysUnlocked);
        assertFalse(unlocked.protectedContent);
        assertEquals("source", unlocked.originProfileKey);
        assertThrows(IllegalArgumentException.class, unlocked::requireOverlayCompatible);
        assertFalse(unlocked.withAlwaysUnlocked(false).alwaysUnlocked);
    }
    @Test public void protectedContentIsExplicitAndCannotUseOverlayPreview() {
        assertFalse(new VirtualDisplaySpec(1280, 720, 160).protectedContent);
        final VirtualDisplaySpec protectedSpec = new VirtualDisplaySpec(1280, 720, 160, true);
        assertTrue(protectedSpec.protectedContent);
        assertThrows(IllegalArgumentException.class, protectedSpec::requireOverlayCompatible);
    }
    @Test public void validatesBeforeAllocatingDisplayResources() {
        assertEquals(2560, new VirtualDisplaySpec(2560, 1080, 160).width);
        assertThrows(IllegalArgumentException.class, () -> new VirtualDisplaySpec(0, 1080, 160));
        assertThrows(IllegalArgumentException.class, () -> new VirtualDisplaySpec(1920, -1, 160));
        assertThrows(IllegalArgumentException.class, () -> new VirtualDisplaySpec(8192, 8192, 160));
        assertThrows(IllegalArgumentException.class, () -> new VirtualDisplaySpec(Integer.MAX_VALUE, 1080, 160));
        assertThrows(IllegalArgumentException.class, () -> new VirtualDisplaySpec(1920, 1080, 79));
        assertThrows(IllegalArgumentException.class, () -> new VirtualDisplaySpec(1920, 1080, 641));
    }

    @Test public void previewHonorsAndroidOverlayLimits() {
        new VirtualDisplaySpec(3840, 2160, 120).requireOverlayCompatible();
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualDisplaySpec(5120, 2160, 160).requireOverlayCompatible());
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualDisplaySpec(1920, 1080, 80).requireOverlayCompatible());
    }

    @Test public void catalogDoesNotInferOwnershipFromDisplayType() {
        final DesktopDisplayInfo owned = PhoneControlPanelControllerTest.display(3, "virtual", true, true);
        final DesktopDisplayInfo foreign = PhoneControlPanelControllerTest.display(4, "virtual", true, false);
        assertEquals(DesktopDisplayOutput.Kind.SIMULATED, owned.target().output.kind);
        assertEquals(DesktopDisplayOutput.ActivationSource.MAGICDESK_REQUESTED,
                owned.target().output.activationSource);
        assertEquals(DesktopDisplayOutput.ActivationSource.ADOPTED_EXISTING,
                foreign.target().output.activationSource);
        assertThrows(IllegalArgumentException.class,
                () -> PhoneControlPanelControllerTest.display(5, "internal", false, false).target());
    }
}
