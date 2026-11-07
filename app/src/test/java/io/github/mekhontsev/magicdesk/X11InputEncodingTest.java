package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class X11InputEncodingTest {
    @Test public void pointerButtonsKeepX11Order() {
        assertEquals(1, X11InputEncoding.button(HostedSurfaceOutput.Button.PRIMARY));
        assertEquals(2, X11InputEncoding.button(HostedSurfaceOutput.Button.MIDDLE));
        assertEquals(3, X11InputEncoding.button(HostedSurfaceOutput.Button.SECONDARY));
        assertEquals(8, X11InputEncoding.button(HostedSurfaceOutput.Button.BACK));
        assertEquals(9, X11InputEncoding.button(HostedSurfaceOutput.Button.FORWARD));
    }

    @Test public void evdevCodesOutsideEightBitX11RangeUseAndroidFallback() {
        assertEquals(0, X11InputEncoding.scanCode(-1));
        assertEquals(0, X11InputEncoding.scanCode(0));
        assertEquals(30, X11InputEncoding.scanCode(30));
        assertEquals(247, X11InputEncoding.scanCode(247));
        assertEquals(0, X11InputEncoding.scanCode(248));
        assertEquals(0, X11InputEncoding.scanCode(700));
    }
}
