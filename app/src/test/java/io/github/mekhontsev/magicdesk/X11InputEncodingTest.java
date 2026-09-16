package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class X11InputEncodingTest {
    @Test public void pointerButtonsKeepX11Order() {
        assertEquals(1, X11InputEncoding.button(HostedSurfaceOutput.Button.PRIMARY));
        assertEquals(2, X11InputEncoding.button(HostedSurfaceOutput.Button.MIDDLE));
        assertEquals(3, X11InputEncoding.button(HostedSurfaceOutput.Button.SECONDARY));
    }

    @Test public void wheelDirectionsAndFractionalStepsStayUnchanged() {
        List<Integer> clicks = new ArrayList<>();
        X11InputEncoding.scroll(0.5f, 1.1f, clicks::add);
        assertEquals(List.of(4, 4, 7), clicks);
        clicks.clear();
        X11InputEncoding.scroll(-1.1f, -0.5f, clicks::add);
        assertEquals(List.of(5, 6, 6), clicks);
        clicks.clear();
        X11InputEncoding.scroll(0, 0, clicks::add);
        assertTrue(clicks.isEmpty());
    }

    @Test public void wheelBatchesAreBoundedPerAxis() {
        List<Integer> clicks = new ArrayList<>();
        X11InputEncoding.scroll(1000, -1000, clicks::add);
        assertEquals(64, clicks.size());
        assertEquals(java.util.Collections.nCopies(32, 5), clicks.subList(0, 32));
        assertEquals(java.util.Collections.nCopies(32, 7), clicks.subList(32, 64));
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
