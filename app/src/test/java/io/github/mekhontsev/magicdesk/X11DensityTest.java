package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class X11DensityTest {
    @Test public void mapsLogicalDensityNotPhysicalPixels() {
        assertEquals(96, X11Density.resolve(160, 100));
        assertEquals(192, X11Density.resolve(320, 100));
        assertEquals(288, X11Density.resolve(320, 150));
        assertEquals(312, X11Density.resolve(520, 100));
        assertEquals(48, X11Density.resolve(160, 50));
        assertEquals(1536, X11Density.resolve(Integer.MAX_VALUE, 200));
        assertThrows(IllegalArgumentException.class, () -> X11Density.resolve(0, 100));
        assertThrows(IllegalArgumentException.class, () -> X11Density.resolve(160, 201));
    }

    @Test public void backgroundHostDoesNotFightFocusedHost() {
        X11Density density = new X11Density(160);
        Object phone = new Object(), monitor = new Object();
        density.update(monitor, 160, true);
        density.update(phone, 520, false);
        assertEquals(96, density.resolve(100));
        density.update(phone, 520, true);
        density.update(monitor, 240, false);
        assertEquals(312, density.resolve(100));
        density.update(phone, 480, false);
        assertEquals(288, density.resolve(100));
        density.release(phone);
        assertEquals(144, density.resolve(100));
        density.release(monitor);
        assertEquals(144, density.resolve(100));
    }
}
