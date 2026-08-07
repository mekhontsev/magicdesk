package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopControlsControllerTest {
    @Test
    public void snapDpiClampsAndRoundsToFourDpiSteps() {
        assertEquals(96, DesktopControlsController.snapDpi(40, 520));
        assertEquals(96, DesktopControlsController.snapDpi(97, 520));
        assertEquals(100, DesktopControlsController.snapDpi(99, 520));
        assertEquals(192, DesktopControlsController.snapDpi(193, 520));
        assertEquals(520, DesktopControlsController.snapDpi(700, 520));
    }

    @Test
    public void snapDpiPreservesAnUnalignedPhysicalMaximum() {
        assertEquals(420, DesktopControlsController.snapDpi(420, 421));
        assertEquals(421, DesktopControlsController.snapDpi(421, 421));
    }
}
