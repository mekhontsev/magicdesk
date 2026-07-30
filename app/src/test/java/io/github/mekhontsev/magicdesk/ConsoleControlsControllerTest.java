package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ConsoleControlsControllerTest {
    @Test
    public void snapDpiClampsAndRoundsToFourDpiSteps() {
        assertEquals(96, ConsoleControlsController.snapDpi(40, 520));
        assertEquals(96, ConsoleControlsController.snapDpi(97, 520));
        assertEquals(100, ConsoleControlsController.snapDpi(99, 520));
        assertEquals(192, ConsoleControlsController.snapDpi(193, 520));
        assertEquals(520, ConsoleControlsController.snapDpi(700, 520));
    }

    @Test
    public void snapDpiPreservesAnUnalignedPhysicalMaximum() {
        assertEquals(420, ConsoleControlsController.snapDpi(420, 421));
        assertEquals(421, ConsoleControlsController.snapDpi(421, 421));
    }
}
