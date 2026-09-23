package io.github.mekhontsev.magicdesk.x11;

import org.junit.Test;
import static org.junit.Assert.*;

public class X11ShellSurfaceTest {
    private static int[] metadata() {
        var value = new int[27];
        value[0] = 2; value[1] = value[2] = 1;
        value[3] = 20; value[5] = 600; value[6] = 40;
        value[7] = -5; value[8] = -6; value[9] = 620; value[10] = 100;
        value[13] = -1; value[25] = 600; value[26] = 40;
        return value;
    }
    @Test public void decodesUnsignedIdsStrutsAndNegativeFamilyOrigin() {
        var value = X11ShellSurface.decode(-1, metadata());
        assertEquals(0xffffffffL, value.id());
        assertEquals(Long.valueOf(0xffffffffL), value.strut().get(2));
        assertEquals(-5, value.paint().left());
        assertEquals(620, value.bounds().right());
        assertThrows(UnsupportedOperationException.class, () -> value.input().clear());
    }
    @Test public void malformedFramesNeverCreateAnApproximateInputRegion() {
        assertThrows(IllegalArgumentException.class, () -> X11ShellSurface.decode(1, new int[24]));
        var value = metadata(); value[2] = 0;
        assertThrows(IllegalArgumentException.class, () -> X11ShellSurface.decode(1, value));
        value[2] = 1; value[3] = Integer.MAX_VALUE;
        assertThrows(ArithmeticException.class, () -> X11ShellSurface.decode(1, value));
    }
}
