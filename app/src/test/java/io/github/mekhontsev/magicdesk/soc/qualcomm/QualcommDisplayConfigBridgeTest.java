package io.github.mekhontsev.magicdesk.soc.qualcomm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public final class QualcommDisplayConfigBridgeTest {
    @Test
    public void parsesConnectedExternalConfigs() throws Exception {
        final QualcommDisplayConfigBridge.Snapshot snapshot =
                QualcommDisplayConfigBridge.parse(
                        "status=connected\n"
                                + "active=1\n"
                                + "mode=0,1920,1080,75\n"
                                + "mode=1,2560,1080,75\n");

        assertTrue(snapshot.available);
        assertTrue(snapshot.connected);
        assertEquals(1, snapshot.activeConfig);
        assertEquals(2, snapshot.configs.size());
        assertEquals("2560x1080@75", snapshot.active().timingKey());
        assertEquals(0, snapshot.findTiming("1920x1080@75").index);
        assertNull(snapshot.findTiming("3840x2160@60"));
    }

    @Test
    public void parsesMissingService() throws Exception {
        final QualcommDisplayConfigBridge.Snapshot snapshot =
                QualcommDisplayConfigBridge.parse("status=missing\n");

        assertFalse(snapshot.available);
        assertFalse(snapshot.connected);
        assertTrue(snapshot.configs.isEmpty());
    }

    @Test(expected = IOException.class)
    public void rejectsMalformedMode() throws Exception {
        QualcommDisplayConfigBridge.parse(
                "status=connected\nactive=0\nmode=bad\n");
    }
}
