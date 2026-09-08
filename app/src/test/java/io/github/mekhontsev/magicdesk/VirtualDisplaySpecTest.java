package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public final class VirtualDisplaySpecTest {
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
        assertEquals(DesktopDisplayTarget.Kind.SIMULATED, owned.target().kind);
        assertEquals(DesktopDisplayTarget.ActivationSource.MAGICDESK_REQUESTED,
                owned.target().activationSource);
        assertEquals(DesktopDisplayTarget.ActivationSource.ADOPTED_EXISTING,
                foreign.target().activationSource);
        assertThrows(IllegalArgumentException.class,
                () -> PhoneControlPanelControllerTest.display(5, "internal", false, false).target());
    }
}
