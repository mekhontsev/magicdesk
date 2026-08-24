package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InternalDisplayDesktopConfigTest {
    @Test
    public void recordsEnabledFrameworkResource() {
        final InternalDisplayDesktopConfig.Snapshot snapshot =
                InternalDisplayDesktopConfig.fromValue(true);

        assertEquals(InternalDisplayDesktopConfig.State.ENABLED, snapshot.state);
        assertTrue(snapshot.detail.contains(
                "config_canInternalDisplayHostDesktops=true"));
    }

    @Test
    public void recordsDisabledResourceWithoutTreatingItAsSupportDecision() {
        final InternalDisplayDesktopConfig.Snapshot snapshot =
                InternalDisplayDesktopConfig.fromValue(false);

        assertEquals(
                InternalDisplayDesktopConfig.State.DISABLED,
                snapshot.state);
        assertTrue(snapshot.detail.contains("diagnostic only"));
    }
}
