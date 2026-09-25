package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class X11DensityTest {
    @Test public void translatesToolkitScaleAndExplicitUserAdjustment() {
        assertEquals(96, X11Density.resolve(1, 100));
        assertEquals(192, X11Density.resolve(2, 100));
        assertEquals(288, X11Density.resolve(2, 150));
        assertEquals(96, X11Density.resolve(2, 50));
        assertEquals(144, X11Density.resolve(1.5, 100));
        assertEquals(125, X11Density.resolve(HostedUiScale.resolve(208, 1541, 797), 100));
        assertEquals(187, X11Density.resolve(HostedUiScale.resolve(208, 1541, 797), 150));
        assertEquals(24, X11Density.resolve(0.1, 100));
        assertEquals(1536, X11Density.resolve(Double.MAX_VALUE, 200));
        assertThrows(IllegalArgumentException.class, () -> new X11Density(0));
        assertThrows(IllegalArgumentException.class, () -> X11Density.resolve(Double.NaN, 100));
        assertThrows(IllegalArgumentException.class, () -> X11Density.resolve(Double.POSITIVE_INFINITY, 100));
        assertThrows(IllegalArgumentException.class, () -> X11Density.resolve(1, 201));
    }

    @Test public void backgroundHostDoesNotFightFocusedHost() {
        X11Density density = new X11Density(1);
        Object phone = new Object(), monitor = new Object();
        density.update(monitor, 1, true);
        density.update(phone, 2, false);
        assertEquals(96, density.resolve(100));
        density.update(phone, 2, true);
        density.update(monitor, 1.5, false);
        assertEquals(192, density.resolve(100));
        density.update(phone, 1, false);
        assertEquals(96, density.resolve(100));
        density.release(phone);
        assertEquals(144, density.resolve(100));
        density.release(monitor);
        assertEquals(144, density.resolve(100));
    }

    @Test public void focusedHostResizeChangesTheCommonScale() {
        Object phone = new Object();
        X11Density density = new X11Density(HostedUiScale.resolve(520, 1216, 2498));
        assertEquals(195, density.resolve(100));
        density.update(phone, HostedUiScale.resolve(520, 1000, 1500), true);
        assertEquals(160, density.resolve(100));
        density.update(phone, HostedUiScale.resolve(520, 1216, 2498), true);
        assertEquals(195, density.resolve(100));
    }
}
