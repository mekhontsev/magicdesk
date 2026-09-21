package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;

public final class DesktopLaunchPolicyTest {
    private DesktopLaunchPolicy resolve(DesktopLaunchPresentation request, AppWindowState saved,
            DesktopLaunchMode live, boolean phone, boolean enabled) {
        return DesktopLaunchPolicy.resolve(request, saved, live, true, true, phone, enabled);
    }

    @Test public void phoneDefaultIsOptInAndDoesNotAffectExternalWorkspaces() {
        var request = DesktopLaunchPresentation.automatic();
        assertEquals(DesktopLaunchMode.WINDOWED, resolve(request, null, null, true, false).mode);
        assertEquals(DesktopLaunchMode.WINDOWED, resolve(request, null, null, false, true).mode);
        assertEquals(DesktopLaunchMode.FULLSCREEN, resolve(request, null, null, true, true).mode);
    }

    @Test public void explicitAndSavedPresentationPrecedePhoneDefault() {
        var bounds = new RelativeWindowBounds(0, 0, 5000, 5000);
        var saved = new AppWindowState(AppWindowState.Mode.WINDOWED, bounds);
        var decision = resolve(DesktopLaunchPresentation.automatic(), saved, null, true, true);
        assertEquals(DesktopLaunchMode.WINDOWED, decision.mode);
        assertSame(bounds, decision.bounds);
        assertTrue(decision.explicitWindowed);
        assertEquals(DesktopLaunchMode.FULLSCREEN, resolve(
                DesktopLaunchPresentation.forMode(DesktopLaunchMode.FULLSCREEN), saved, null, true, false).mode);
        assertEquals(DesktopLaunchMode.WINDOWED, resolve(
                DesktopLaunchPresentation.forMode(DesktopLaunchMode.WINDOWED), null, null, true, true).mode);
        assertEquals(DesktopLaunchMode.WINDOWED, resolve(DesktopLaunchPresentation.automatic(),
                new AppWindowState(null, bounds), null, true, true).mode);
    }

    @Test public void liveTasksKeepTheirModeUnlessExplicitlyRequested() {
        var automatic = DesktopLaunchPresentation.automatic();
        assertEquals(DesktopLaunchMode.WINDOWED,
                resolve(automatic, null, DesktopLaunchMode.WINDOWED, true, true).mode);
        assertEquals(DesktopLaunchMode.FULLSCREEN,
                resolve(automatic, null, DesktopLaunchMode.FULLSCREEN, true, false).mode);
        assertFalse(resolve(automatic, null, DesktopLaunchMode.WINDOWED, true, true).explicitWindowed);
        assertEquals(DesktopLaunchMode.FULLSCREEN, resolve(
                automatic.withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW), null,
                DesktopLaunchMode.WINDOWED, true, true).mode);
    }
}
