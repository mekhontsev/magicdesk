package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Exercises the production boundaries without input devices or Android services. */
public final class DesktopInputSessionBoundaryTest {
    @Test
    public void ownershipChangesReconcileInputWithoutAnAbsolutePointer() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display { static final int INVALID_DISPLAY = -1; }
                boolean mDestroyed, mDesktopPrepared;
                int mDesktopDisplayId = -1, mClosingInputDisplayId = -1;
                final List<String> events = new ArrayList<>();
                void updateShowImeOverride() { events.add("ime"); }
                void updateInputBridges() { events.add("bridges"); }
                void refreshDesktopInputSources() { events.add("sources"); }
                boolean ownsExternalDesktop() { return mDesktopDisplayId > 0; }
                public static void verify() {
                    Fixture f = new Fixture();
                    f.setDesktopDisplay(7, true);
                    check(f.events.equals(List.of("ime", "bridges", "sources")),
                            "external input setup changed: " + f.events);
                    f.mDesktopPrepared = true;
                    f.events.clear();
                    f.setDesktopDisplay(7, false);
                    check(f.mDesktopPrepared && f.events.isEmpty(),
                            "unchanged ownership restarted input");
                    f.mClosingInputDisplayId = 7;
                    f.events.clear();
                    f.setDesktopDisplay(-1, true);
                    check(!f.mDesktopPrepared && f.mClosingInputDisplayId == -1,
                            "closed desktop retained readiness or suspension");
                    check(f.events.equals(List.of("ime", "bridges")),
                            "close skipped shared cleanup: " + f.events);
                    f.events.clear();
                    f.setDesktopDisplay(0, true);
                    check(f.events.equals(List.of("ime", "bridges")),
                            "phone desktop started external routing");
                    f.events.clear();
                    f.mDestroyed = true;
                    f.setDesktopDisplay(7, true);
                    check(f.events.isEmpty(), "destroyed runtime accepted ownership");
                }
                """ + RuntimeSourceFixture.methods("RuntimeDesktopInputCoordinator",
                        "setDesktopDisplay", "clearCompletedInputClose"));
    }

    @Test
    public void preparationStartsOnceAndCloseRejectsLateReadiness() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display {
                    static final int INVALID_DISPLAY = -1, DEFAULT_DISPLAY = 0;
                }
                boolean mDestroyed, mDesktopPrepared;
                int mDesktopDisplayId = 7, mClosingInputDisplayId = -1;
                int updates;
                boolean mPointerReleaseExpected;
                final Session mInputSession = new Session();
                class Session { void stop(Runnable done) { updates++; done.run(); } }
                void updateInputBridges() { updates++; }
                public static void verify() {
                    Fixture f = new Fixture();
                    f.onDesktopPrepared(8);
                    check(f.updates == 0, "foreign preparation enabled input");
                    f.onDesktopPrepared(7);
                    f.onDesktopPrepared(7);
                    check(f.mDesktopPrepared && f.updates == 1, "preparation restarted input");
                    f.releaseForSessionClose(8, () -> {});
                    check(f.updates == 1, "foreign close stopped input");
                    f.releaseForSessionClose(7, () -> {});
                    check(!f.mDesktopPrepared && f.updates == 2, "close did not stop input");
                    f.onDesktopPrepared(7);
                    check(!f.mDesktopPrepared && f.updates == 2, "late readiness reopened input");
                    f.mDesktopDisplayId = -1;
                    f.onDesktopPrepared(-1);
                    check(f.updates == 2, "inactive display accepted preparation");
                    f.clearCompletedInputClose(-1);
                    f.mDesktopDisplayId = 7;
                    f.onDesktopPrepared(7);
                    check(f.mDesktopPrepared && f.updates == 3, "next session cannot acquire input");
                    f.mDestroyed = true;
                    f.releaseForSessionClose(7, () -> {});
                    check(f.updates == 3, "destroyed runtime accepted close");
                }
                """ + RuntimeSourceFixture.methods("RuntimeDesktopInputCoordinator",
                        "onDesktopPrepared", "releaseForSessionClose",
                        "isActiveDesktopDisplay", "clearCompletedInputClose"));
    }

    @Test
    public void parkedWorkspaceMustFinishBeforeInputStarts() throws Exception {
        RuntimeSourceFixture.verify("""
                static class DesktopSessionSnapshot {
                    boolean restoreWorkspace = true;
                    int host = 10;
                    DesktopSessionSnapshot policy() { return this; }
                    int hostTaskId() { return host; }
                }
                final Object mLock = new Object();
                boolean mRestoreInProgress;
                int mRestoreCompletedHostTaskId = -1;
                final Map<Integer, String> mParked = new HashMap<>();
                public static void verify() {
                    Fixture f = new Fixture();
                    DesktopSessionSnapshot s = new DesktopSessionSnapshot();
                    check(f.isWorkspacePrepared(s), "empty workspace is not ready");
                    f.mParked.put(1, "app");
                    check(!f.isWorkspacePrepared(s), "pending restore was ignored");
                    f.mRestoreInProgress = true;
                    check(!f.isWorkspacePrepared(s), "queued restore counted as complete");
                    f.mRestoreInProgress = false;
                    f.mRestoreCompletedHostTaskId = 10;
                    check(f.isWorkspacePrepared(s), "failed individual app blocked input forever");
                    s.host = 11;
                    check(!f.isWorkspacePrepared(s), "old host completion reused for new session");
                    s.restoreWorkspace = false;
                    check(f.isWorkspacePrepared(s), "isolated test waited for parked apps");
                }
                """ + RuntimeSourceFixture.methods("DesktopTaskParkingController",
                        "isWorkspacePrepared"));
    }
}
