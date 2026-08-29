package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class NativeInputBridgeStatsTest {
    @Test
    public void parsesRequestedNativeSnapshot() {
        final NativeInputBridgeStats stats = NativeInputBridgeStats.parse(
                "MAGICDESK_MOUSE_STATS request=17 physicalReports=42"
                        + " forwardedReports=42 writeErrors=0",
                "MAGICDESK_MOUSE_STATS");

        assertEquals(17L, stats.requestId);
        assertEquals(
                "physicalReports=42 forwardedReports=42 writeErrors=0",
                stats.detail);
    }

    @Test
    public void rejectsUnrelatedOrMalformedResponses() {
        assertNull(NativeInputBridgeStats.parse(
                "MAGICDESK_KEYBOARD_STATS request=4 physicalEvents=8",
                "MAGICDESK_MOUSE_STATS"));
        assertNull(NativeInputBridgeStats.parse(
                "MAGICDESK_MOUSE_STATS physicalReports=42",
                "MAGICDESK_MOUSE_STATS"));
    }
}
