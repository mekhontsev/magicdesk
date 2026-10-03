package io.github.mekhontsev.magicdesk;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.mekhontsev.magicdesk.HostedLaunchWindows.Role.*;

public final class HostedLaunchWindowsTest {
    private static HostedLaunchWindows.Window window(long id, HostedLaunchWindows.Role role) {
        return new HostedLaunchWindows.Window(id, true, role);
    }
    @Test public void splashAndEmptyGapDoNotConsumeMainPresentation() {
        var launch = new HostedLaunchWindows();
        assertTrue(launch.update(List.of()).isEmpty());
        assertEquals(List.of(new HostedLaunchWindows.Offer(1, false, false)), launch.update(List.of(window(1, SPLASH))));
        assertTrue(launch.update(List.of(window(1, SPLASH))).isEmpty());
        assertTrue(launch.update(List.of()).isEmpty());
        assertEquals(List.of(new HostedLaunchWindows.Offer(2, true, false)), launch.update(List.of(window(2, APPLICATION))));
        assertTrue(launch.update(List.of(window(3, APPLICATION))).isEmpty());
    }
    @Test public void recoveryDialogRemainsUsableWhileMainIsAbsent() {
        var launch = new HostedLaunchWindows();
        assertFalse(launch.update(List.of(window(1, DIALOG))).get(0).primary());
        assertTrue(launch.update(List.of(window(1, DIALOG))).isEmpty());
        assertEquals(List.of(new HostedLaunchWindows.Offer(2, true, false)),
                launch.update(List.of(window(1, DIALOG), window(2, APPLICATION))));
    }
    @Test public void unknownWindowIsNotGuessedToBeSplash() {
        var launch = new HostedLaunchWindows();
        assertTrue(launch.update(List.of(window(1, UNCLASSIFIED))).get(0).primary());
    }
    @Test public void noTemporaryHostIsCreatedWhenMainAlreadyExists() {
        var launch = new HostedLaunchWindows();
        assertEquals(List.of(new HostedLaunchWindows.Offer(2, true, false)),
                launch.update(List.of(window(1, SPLASH), window(2, APPLICATION), window(3, DIALOG))));
    }
    @Test public void unmappingAndRemappingSplashReoffersItsTemporaryHost() {
        var launch = new HostedLaunchWindows();
        launch.update(List.of(window(1, SPLASH)));
        assertTrue(launch.update(List.of(new HostedLaunchWindows.Window(1, false, SPLASH))).isEmpty());
        assertEquals(List.of(new HostedLaunchWindows.Offer(1, false, false)), launch.update(List.of(window(1, SPLASH))));
    }
    @Test public void rolePromotionReplacesTemporaryHostWithoutClosingClient() {
        var launch = new HostedLaunchWindows();
        launch.update(List.of(window(1, SPLASH)));
        assertEquals(List.of(new HostedLaunchWindows.Offer(1, true, true)), launch.update(List.of(window(1, APPLICATION))));
    }
    @Test public void unmappedMainDoesNotSkipVisibleStartupDialog() {
        var launch = new HostedLaunchWindows();
        assertEquals(List.of(new HostedLaunchWindows.Offer(2, false, false)), launch.update(List.of(
                new HostedLaunchWindows.Window(1, false, APPLICATION), window(2, DIALOG))));
    }
}
