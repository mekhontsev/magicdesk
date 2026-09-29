package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostedApplicationLifetimeTest {
    @Test public void destroyingLastWindowDoesNotCancelCommandCleanup() {
        var lifetime = new HostedApplicationLifetime();
        lifetime.windows(true, true);
        lifetime.windows(false, false);
        assertFalse(lifetime.quiescent());
        lifetime.completeLaunch();
        assertTrue(lifetime.quiescent());
    }

    @Test public void detachedLauncherDoesNotOwnRemainingWindows() {
        var lifetime = new HostedApplicationLifetime();
        lifetime.completeLaunch();
        assertFalse(lifetime.quiescent());
        lifetime.windows(true, true);
        assertFalse(lifetime.quiescent());
        lifetime.windows(false, false);
        assertTrue(lifetime.quiescent());
    }

    @Test public void startupGapCanBeFollowedByAnotherWindow() {
        var lifetime = new HostedApplicationLifetime();
        lifetime.windows(true, true);
        lifetime.windows(false, false);
        assertFalse(lifetime.quiescent());
        lifetime.windows(true, true);
        lifetime.completeLaunch();
        assertFalse(lifetime.quiescent());
        assertTrue(lifetime.hadWindows());
        assertTrue(lifetime.launchFinished());
    }

    @Test public void unmappedWindowStillRetainsSession() {
        var lifetime = new HostedApplicationLifetime();
        lifetime.windows(true, true);
        lifetime.completeLaunch();
        lifetime.windows(true, false);
        assertFalse(lifetime.quiescent());
    }

    @Test public void unseenApplicationRemainsSubjectToLaunchDeadline() {
        var lifetime = new HostedApplicationLifetime();
        lifetime.windows(true, false);
        lifetime.windows(false, false);
        lifetime.completeLaunch();
        assertFalse(lifetime.hadWindows());
        assertFalse(lifetime.quiescent());
    }
}
