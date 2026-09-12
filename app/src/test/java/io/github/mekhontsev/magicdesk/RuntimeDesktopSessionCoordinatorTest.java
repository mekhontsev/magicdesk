package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeDesktopSessionCoordinatorTest {
    @Test
    public void onlyUnexpectedRemovalStartsEventRecovery() throws Exception {
        RuntimeSourceFixture.verify("""
                Map<Integer, DesktopDisplayTarget> mOwnedTargets = Map.of(100, new DesktopDisplayTarget());
                Set<Integer> mExpectedRemovedDisplays = new HashSet<>();
                int recoveries, homeReleases, refreshes;
                static class Display { static final int INVALID_DISPLAY = -1, DEFAULT_DISPLAY = 0; }
                static class DesktopDisplayTarget {
                    enum Kind { SIMULATED }
                    Kind kind = Kind.SIMULATED;
                }
                static class DesktopSessionSnapshot {
                    DesktopDisplayTarget targetForWorkspace(int id) { return new DesktopDisplayTarget(); }
                    int activeWorkspaceDisplayId() { return 100; }
                }
                static class DesktopRuntimeBridge {
                    static int closes;
                    static boolean hasWorkspace(int id) { return id == 100; }
                    static DesktopSessionSnapshot getSessionSnapshot(int displayId) { return new DesktopSessionSnapshot(); }
                    static void closeDesktopWorkspace(int id) { closes++; }
                }
                static class PhoneTouchpadController { static void release(int id) {} }
                static class SimulatedDesktopDisplayController { static void release(int id) {} }
                static class DesktopOperations {
                    static boolean transitioning;
                    static boolean isSessionTransitionInProgress() { return transitioning; }
                }
                void releaseHomeLeaseAfterSessionLoss(int id) { homeReleases++; }
                void beginRemovedDisplayRecovery(int id, boolean restore) { recoveries++; }
                void refreshOwnership() { refreshes++; }
                void schedulePhoneTaskRecovery() {}
                public static void verify() {
                    Fixture expected = new Fixture();
                    expected.mExpectedRemovedDisplays.add(100);
                    expected.handleDisplayStateChanged(100, true);
                    check(expected.recoveries == 0, "Close acquired a second recovery owner");
                    check(expected.homeReleases == 0, "Close released HOME twice");
                    check(expected.mExpectedRemovedDisplays.isEmpty(), "expected marker not consumed");
                    check(expected.refreshes == 1 && DesktopRuntimeBridge.closes == 1,
                            "expected removal skipped session cleanup");
                    Fixture unexpected = new Fixture();
                    unexpected.handleDisplayStateChanged(100, true);
                    check(unexpected.recoveries == 1, "unexpected loss omitted recovery");
                    check(unexpected.homeReleases == 1, "unexpected loss retained HOME");
                    DesktopOperations.transitioning = true;
                    Fixture closing = new Fixture();
                    closing.mExpectedRemovedDisplays.add(100);
                    closing.handleDisplayStateChanged(100, true);
                    check(closing.recoveries == 0 && closing.homeReleases == 0,
                            "display loss after host unregister raced explicit Close");
                }
                """ + RuntimeSourceFixture.methods("RuntimeDesktopSessionCoordinator",
                        "handleDisplayStateChanged"));
    }

    @Test
    public void queuedPhonePanelRestoreYieldsToNewSession() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display { static final int INVALID_DISPLAY = -1; }
                static class Operations {
                    Runnable queued;
                    void execute(Runnable work) { queued = work; }
                }
                static class PhoneUi {
                    int restores;
                    void setPhoneScreenOff(boolean off, int display) { restores++; }
                }
                static class DesktopSessionSnapshot {
                    boolean starting, active;
                    Object target() { return starting ? this : null; }
                    boolean hasHost() { return active; }
                }
                static class DesktopRuntimeBridge {
                    static DesktopSessionSnapshot session = new DesktopSessionSnapshot();
                    static boolean hasWorkspaces() { return session.starting || session.active; }
                }
                static class PhoneControlPanelLauncher {
                    static int opens;
                    static void openOnPhoneWithShell() { opens++; }
                }
                static Operations mOperations = new Operations();
                static PhoneUi mPhoneUi = new PhoneUi();
                static boolean transitioning;
                static boolean isSessionTransitionInProgress() { return transitioning; }
                public static void verify() {
                    Fixture runtime = new Fixture();
                    runtime.restorePhoneAfterExternalDesktop();
                    transitioning = true;
                    mOperations.queued.run();
                    check(mPhoneUi.restores == 0 && PhoneControlPanelLauncher.opens == 0,
                            "queued restore interfered with Start before target publication");
                    transitioning = false;
                    DesktopRuntimeBridge.session.starting = true;
                    runtime.restorePhoneAfterExternalDesktop();
                    mOperations.queued.run();
                    check(PhoneControlPanelLauncher.opens == 0, "queued restore covered a new target");
                    DesktopRuntimeBridge.session.starting = false;
                    DesktopRuntimeBridge.session.active = true;
                    runtime.restorePhoneAfterExternalDesktop();
                    mOperations.queued.run();
                    check(PhoneControlPanelLauncher.opens == 0, "queued restore covered a new host");
                    DesktopRuntimeBridge.session.active = false;
                    runtime.restorePhoneAfterExternalDesktop();
                    mOperations.queued.run();
                    check(mPhoneUi.restores == 1 && PhoneControlPanelLauncher.opens == 1,
                            "settled display loss did not restore the phone");
                }
                """ + RuntimeSourceFixture.methods("DesktopSessionTransitionCoordinator",
                        "restorePhoneAfterExternalDesktop"));
    }

    @Test
    public void lateRecoveryCallbacksCannotAffectANewerRequest() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String TAG = "test";
                boolean mDestroyed;
                Map<Integer, RemovedDisplayRecovery> mRemovedDisplayRecoveries = new HashMap<>();
                boolean canRecover(RemovedDisplayRecovery recovery) { return true; }
                int clears, retries;
                static class RemovedDisplayRecovery {
                    int displayId = 100;
                    boolean restorePhonePanel = true;
                    boolean shouldContinue(Object session) { return true; }
                    boolean finish(PhoneDesktopTaskRecovery.Result result) { return false; }
                }
                static class DesktopRuntimeBridge {
                    static boolean hasWorkspaces() { return false; }
                }
                static class PhoneDesktopTaskRecovery {
                    static class Result {
                        boolean success, pending, cancelled;
                        String message = "unavailable task";
                    }
                }
                static class Log { static void w(String tag, String message) {} }
                static class CompatibilityDiagnostics {
                    static int errors;
                    static void record(Object... args) { errors++; }
                }
                static class DesktopOperations {
                    static int panels;
                    static void restorePhoneAfterExternalDesktop() { panels++; }
                }
                void clearRemovedDisplayRecovery(RemovedDisplayRecovery r) { clears++; mRemovedDisplayRecoveries.remove(r.displayId, r); }
                void schedulePhoneTaskRecovery() { retries++; }
                public static void verify() {
                    Fixture runtime = new Fixture();
                    RemovedDisplayRecovery old = new RemovedDisplayRecovery();
                    RemovedDisplayRecovery current = new RemovedDisplayRecovery();
                    runtime.mRemovedDisplayRecoveries.put(100, current);
                    PhoneDesktopTaskRecovery.Result failure = new PhoneDesktopTaskRecovery.Result();
                    runtime.finishRemovedDisplayRecovery(old, failure);
                    check(runtime.mRemovedDisplayRecoveries.get(100) == current, "late callback cleared newer recovery");
                    check(runtime.clears == 0 && CompatibilityDiagnostics.errors == 0
                            && DesktopOperations.panels == 0, "late callback affected phone UI or diagnostics");
                    runtime.finishRemovedDisplayRecovery(current, failure);
                    check(runtime.mRemovedDisplayRecoveries.isEmpty(), "failure retained recovery owner");
                    check(runtime.retries == 0 && CompatibilityDiagnostics.errors == 1,
                            "terminal failure scheduled another attempt");
                    check(DesktopOperations.panels == 1, "failure prevented return to phone controls");
                    runtime.finishRemovedDisplayRecovery(current, failure);
                    check(CompatibilityDiagnostics.errors == 1 && DesktopOperations.panels == 1,
                            "terminal callback was replayed");
                }
                """ + RuntimeSourceFixture.methods("RuntimeDesktopSessionCoordinator",
                        "finishRemovedDisplayRecovery"));
    }

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
    public void successfulRecoveryDoesNotRepeatItsOwnTaskEvents() {
        final RuntimeDesktopSessionCoordinator.RemovedDisplayRecovery recovery = recovery();
        assertTrue(recovery.begin());
        assertFalse(recovery.begin());
        assertFalse(recovery.finish(PhoneDesktopTaskRecovery.Result.success("settled")));
        assertFalse(recovery.begin());
    }

    @Test
    public void unavailableRepositoryTaskIsATerminalFailure() {
        final RuntimeDesktopSessionCoordinator.RemovedDisplayRecovery recovery = recovery();
        assertTrue(recovery.begin());
        assertFalse(recovery.begin());
        assertFalse(recovery.finish(PhoneDesktopTaskRecovery.Result.failure("unavailable task")));
        assertFalse(recovery.begin());
        assertFalse(recovery.shouldContinue(DesktopSessionSnapshot.empty()));
    }

    @Test
    public void pendingMigrationCanRetryAfterTaskEventOrWatchdog() {
        final RuntimeDesktopSessionCoordinator.RemovedDisplayRecovery recovery = recovery();
        assertTrue(recovery.begin());
        assertFalse(recovery.begin());
        assertTrue(recovery.finish(PhoneDesktopTaskRecovery.Result.pending("migrating")));
        assertTrue(recovery.begin());
        assertFalse(recovery.finish(PhoneDesktopTaskRecovery.Result.pending("migrating")));
        recovery.allowUnsettled = true;
        assertTrue(recovery.begin());
    }

    @Test
    public void newPhoneTargetCancelsRecoveryBeforeHostCreation() {
        final RuntimeDesktopSessionCoordinator.RemovedDisplayRecovery recovery = recovery();
        assertTrue(recovery.begin());
        assertFalse(recovery.shouldContinue(DesktopSessionSnapshot.empty()
                .noteTarget(DesktopDisplayTarget.phone())));
        assertFalse(recovery.finish(PhoneDesktopTaskRecovery.Result.pending("migrating")));
        assertFalse(recovery.shouldContinue(DesktopSessionSnapshot.empty()));
        assertFalse(recovery.begin());
    }

    @Test
    public void newExternalTargetAlsoCancelsPhoneRecovery() {
        final RuntimeDesktopSessionCoordinator.RemovedDisplayRecovery recovery = recovery();
        assertFalse(recovery.shouldContinue(DesktopSessionSnapshot.empty()
                .noteTarget(DesktopDisplayTarget.simulated(101))));
        assertFalse(recovery.begin());
    }

    @Test
    public void newResidencyOnRemovedDisplayCancelsOldRecovery() {
        final RuntimeDesktopSessionCoordinator.RemovedDisplayRecovery recovery = recovery();
        assertFalse(recovery.shouldContinue(DesktopSessionSnapshot.empty()
                .noteTarget(DesktopDisplayTarget.simulated(100)).registerHost(100, 42)));
        assertFalse(recovery.shouldContinue(DesktopSessionSnapshot.empty()));
        assertFalse(recovery.begin());
    }

    @Test
    public void cancelledRecoveryCannotRestartAfterLatePendingResult() {
        final RuntimeDesktopSessionCoordinator.RemovedDisplayRecovery old = recovery();
        assertTrue(old.begin());
        assertFalse(old.begin());
        old.cancel();
        final RuntimeDesktopSessionCoordinator.RemovedDisplayRecovery current = recovery();
        assertFalse(old.finish(PhoneDesktopTaskRecovery.Result.pending("migrating")));
        assertFalse(old.begin());
        assertTrue(current.begin());
    }

    private static RuntimeDesktopSessionCoordinator.RemovedDisplayRecovery recovery() {
        return new RuntimeDesktopSessionCoordinator.RemovedDisplayRecovery(100, true, true);
    }
}
