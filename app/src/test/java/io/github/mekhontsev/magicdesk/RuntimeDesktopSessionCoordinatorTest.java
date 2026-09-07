package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeDesktopSessionCoordinatorTest {
    @Test
    public void sessionLossDoesNotRaceTheExplicitTransitionOwner() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String TAG = "test";
                static class DesktopOperations {
                    static boolean transitioning;
                    static boolean isSessionTransitionInProgress() { return transitioning; }
                }
                static class DesktopHomeRoleLease {
                    static int releases;
                    static boolean releaseAfterSessionLoss(int display) throws IOException {
                        releases++; return true;
                    }
                }
                static class Log {
                    static void i(String tag, String message) {}
                    static void w(String tag, String message, Throwable e) {}
                }
                static class CompatibilityDiagnostics { static void record(Object... args) {} }
                public static void verify() {
                    DesktopOperations.transitioning = true;
                    releaseHomeLeaseAfterSessionLoss(7);
                    check(DesktopHomeRoleLease.releases == 0, "event disabled a live transition host");
                    DesktopOperations.transitioning = false;
                    releaseHomeLeaseAfterSessionLoss(7);
                    check(DesktopHomeRoleLease.releases == 1, "unexpected loss did not recover HOME");
                }
                """ + RuntimeSourceFixture.methods("RuntimeDesktopSessionCoordinator",
                        "releaseHomeLeaseAfterSessionLoss"));
    }

    @Test
    public void recognizesOwnedDisplayAfterTargetMetadataWasLost() {
        assertTrue(RuntimeDesktopSessionCoordinator.isExternalDesktopRemoval(
                true, 100, 100, null, false));
    }

    @Test
    public void recognizesKnownWirelessDisplayAfterOwnershipWasCleared() {
        assertTrue(RuntimeDesktopSessionCoordinator.isExternalDesktopRemoval(
                true,
                100,
                -1,
                DesktopDisplayTarget.wireless(100),
                false));
    }

    @Test
    public void recognizesOnlyActiveSimulatedDisplayRemoval() {
        assertTrue(RuntimeDesktopSessionCoordinator.isExternalDesktopRemoval(
                true,
                100,
                100,
                DesktopDisplayTarget.simulated(100),
                true));
        assertFalse(RuntimeDesktopSessionCoordinator.isExternalDesktopRemoval(
                true,
                100,
                100,
                DesktopDisplayTarget.simulated(100),
                false));
    }

    @Test
    public void ignoresUnownedDisplayRemoval() {
        assertFalse(RuntimeDesktopSessionCoordinator.isExternalDesktopRemoval(
                true, 100, -1, null, false));
    }

    @Test
    public void phoneRecoveryCompletesOnlyAfterFinalSettledPass() {
        assertFalse(RuntimeDesktopSessionCoordinator.isPhoneRecoveryComplete(
                false, false));
        assertFalse(RuntimeDesktopSessionCoordinator.isPhoneRecoveryComplete(
                true, true));
        assertTrue(RuntimeDesktopSessionCoordinator.isPhoneRecoveryComplete(
                true, false));
    }
}
