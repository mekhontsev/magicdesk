package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Map;

import org.junit.Test;

public final class DesktopSelfTestCapabilityAuditTest {
    @Test
    public void parsesStateAndOptionalDetail() {
        final Map<String, DesktopSelfTestCapabilityAudit.ProbeEntry> values =
                DesktopSelfTestCapabilityAudit.parse(
                        "format=1\n"
                                + "tasks.read=granted | display0_count=4\n"
                                + "vendor.phone_screen=present\n"
                                + "invalid line\n");

        assertEquals("granted", values.get("tasks.read").state);
        assertEquals("display0_count=4", values.get("tasks.read").detail);
        assertEquals("present", values.get("vendor.phone_screen").state);
        assertEquals("", values.get("vendor.phone_screen").detail);
        assertFalse(values.containsKey("invalid line"));
    }
}
