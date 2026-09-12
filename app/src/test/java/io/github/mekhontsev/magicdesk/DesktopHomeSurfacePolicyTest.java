package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static io.github.mekhontsev.magicdesk.DesktopHomeSurfaceRouter.Surface.*;

import java.util.List;
import org.junit.Test;

public final class DesktopHomeSurfacePolicyTest {
    @Test public void secondaryHomeCannotCoverPrimaryDisplay() {
        assertFalse(DesktopHomeSurfaceRouter.acceptsHomeIntent(0, true));
        assertFalse(DesktopHomeSurfaceRouter.acceptsHomeIntent(-1, true));
        assertTrue(DesktopHomeSurfaceRouter.acceptsHomeIntent(0, false));
        for (int display : new int[]{1, 2, 7}) {
            assertTrue(DesktopHomeSurfaceRouter.acceptsHomeIntent(display, true));
            assertTrue(DesktopHomeSurfaceRouter.acceptsHomeIntent(display, false));
        }
    }

    @Test public void invalidNewIntentDoesNotFinishExistingHome() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Intent {}
                static class Parent { void onNewIntent(Intent intent) {} }
                static class FullscreenStartController { static boolean isReleasing() { return false; }
                    static boolean canHost(Object host) { return true; } }
                static class DesktopHomeRoleLease { static boolean isReleasingForDisplay(int display) { return false; } }
                static class Log { static void i(String tag, String message) {} }
                static class Host extends Parent {
                    final String TAG = "test";
                    int mExpectedDisplayId, changes;
                    boolean accepted, mHomeDelegate;
                    class Start { void newIntent(Intent intent) { changes++; } }
                    Start mHomeStart;
                    boolean acceptsHomeIntent(Intent intent) { return accepted; }
                    boolean hasRequiredHomeLease() { return true; }
                    void setIntent(Intent intent) { changes++; }
                    void recreate() { changes++; }
                    void finishAndRemoveTask() { throw new AssertionError("valid HOME destroyed"); }
                    void handleLaunchAction(Intent intent) { changes++; }
                """ + RuntimeSourceFixture.methods("DesktopShellActivity", "onNewIntent") + """
                }
                public static void verify() {
                    Host host = new Host();
                    host.onNewIntent(new Intent());
                    check(host.changes == 0, "desktop reacted to misrouted HOME");
                    host.mHomeStart = host.new Start();
                    host.onNewIntent(new Intent());
                    check(host.changes == 0, "Start reacted to misrouted HOME");
                    host.accepted = true;
                    host.onNewIntent(new Intent());
                    check(host.changes == 1, "valid HOME no longer reaches Start");
                }
                """);
    }

    @Test public void launcherAndDesktopUseIdenticalComponentAdmission() throws Exception {
        RuntimeSourceFixture.verify("""
                enum Surface { SYSTEM, LAUNCHER, DESKTOP }
                record Selection(Surface primary, boolean secondaryHome) {}
                static class PackageManager {
                    static final int COMPONENT_ENABLED_STATE_ENABLED = 1;
                    static final int COMPONENT_ENABLED_STATE_DISABLED = 2;
                }
                static int phone, secondary;
                static void apply(int p, int s) { phone = p; secondary = s; }
                public static void verify() throws Exception {
                    for (Surface surface : List.of(Surface.LAUNCHER, Surface.DESKTOP,
                            Surface.LAUNCHER, Surface.DESKTOP)) {
                        select(new Selection(surface, true));
                        check(phone == 1 && secondary == 1, "workspace change replaced HOME");
                    }
                    disableHomeSurfaces();
                    check(phone == 2 && secondary == 2, "last close retained a HOME candidate");
                }
                """ + RuntimeSourceFixture.methods("DesktopHomeSurfaceRouter",
                        "select", "disableHomeSurfaces"));
    }

    @Test public void noWorkspaceRequiresNoMagicDeskHome() {
        final var selection = DesktopHomeSurfaceRouter.forWorkspaces(List.of());
        assertEquals(SYSTEM, selection.primary);
        assertEquals(SYSTEM, selection.surfaceOn(7));
        assertFalse(selection.secondaryHome);
    }

    @Test public void secondaryWorkspaceUsesLauncherOnDefaultDisplay() {
        final var selection = DesktopHomeSurfaceRouter.forWorkspaces(
                List.of(DesktopDisplayTarget.wired(7)));
        assertEquals(LAUNCHER, selection.primary);
        assertEquals(DESKTOP, selection.surfaceOn(7));
        assertEquals(LAUNCHER, selection.surfaceOn(8));
        assertTrue(selection.secondaryHome);
    }

    @Test public void defaultWorkspaceKeepsLauncherOnUnassignedDisplays() {
        final var selection = DesktopHomeSurfaceRouter.forWorkspaces(
                List.of(DesktopDisplayTarget.phone()));
        assertEquals(DESKTOP, selection.primary);
        assertEquals(LAUNCHER, selection.surfaceOn(7));
        assertTrue(selection.secondaryHome);
    }

    @Test public void defaultAndSecondaryDesktopsAreIndependentOfOrderAndOutput() {
        final var phone = DesktopDisplayTarget.phone();
        final var external = DesktopDisplayTarget.wired(7);
        for (final var targets : List.of(List.of(phone, external), List.of(external, phone))) {
            final var selection = DesktopHomeSurfaceRouter.forWorkspaces(targets);
            assertEquals(DESKTOP, selection.primary);
            assertEquals(DESKTOP, selection.surfaceOn(7));
            assertTrue(selection.secondaryHome);
        }
        final var routed = DesktopDisplayTarget.restore(DesktopDisplayOutput.Kind.WIRED,
                12, 7, "", DesktopDisplayOutput.ActivationSource.ADOPTED_EXISTING);
        final var selection = DesktopHomeSurfaceRouter.forWorkspaces(List.of(routed));
        assertEquals(DESKTOP, selection.surfaceOn(12));
        assertEquals(LAUNCHER, selection.surfaceOn(7));
    }

    @Test public void severalBuiltInPanelsUseTheSameResidencyPolicy() {
        final var selection = DesktopHomeSurfaceRouter.forWorkspaces(List.of(
                DesktopDisplayTarget.builtIn(0), DesktopDisplayTarget.builtIn(1),
                DesktopDisplayTarget.builtIn(2)));
        for (int display = 0; display <= 2; display++) {
            assertEquals(DESKTOP, selection.surfaceOn(display));
        }
        assertTrue(selection.secondaryHome);
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateResidencyCannotAdmitTwoHosts() {
        DesktopHomeSurfaceRouter.forWorkspaces(List.of(
                DesktopDisplayTarget.wired(7), DesktopDisplayTarget.wired(7)));
    }
}
