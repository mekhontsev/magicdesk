package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class NubiaVendorProbeInstrumentationTest {
    @Test
    public void defaultProbeSkipsSameValueWriteBeforeAccessingAndroidSettings() {
        assertEquals("skipped",
                new NubiaVendorProbeInstrumentation().probeGlobalSettingWrite());
    }
}
