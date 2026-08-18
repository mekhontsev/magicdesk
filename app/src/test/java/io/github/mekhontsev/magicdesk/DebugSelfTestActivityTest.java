package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class DebugSelfTestActivityTest {
    @Test
    public void acceptsOnlyExplicitDebugTargets() {
        assertEquals(DebugSelfTestActivity.LaunchTarget.PHONE,
                DebugSelfTestActivity.LaunchTarget.parse("phone"));
        assertEquals(DebugSelfTestActivity.LaunchTarget.SIMULATED,
                DebugSelfTestActivity.LaunchTarget.parse("SIMULATED"));
        assertEquals(DebugSelfTestActivity.LaunchTarget.WIRED,
                DebugSelfTestActivity.LaunchTarget.parse("wired"));
        assertEquals(DebugSelfTestActivity.LaunchTarget.WIRELESS,
                DebugSelfTestActivity.LaunchTarget.parse("WIRELESS"));
    }

    @Test
    public void invalidDebugTargetDoesNotFallBackToSimulated() {
        assertNull(DebugSelfTestActivity.LaunchTarget.parse(null));
        assertNull(DebugSelfTestActivity.LaunchTarget.parse(""));
        assertNull(DebugSelfTestActivity.LaunchTarget.parse("EXTERNAL"));
        assertNull(DebugSelfTestActivity.LaunchTarget.parse("typo"));
    }
}
