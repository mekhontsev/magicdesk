package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Exercises the production boundaries without input devices or Android services. */
public final class DesktopInputSessionBoundaryTest {
    @Test
    public void preparationStartsOnceAndCloseRejectsLateReadiness() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display { static final int INVALID_DISPLAY = -1; }
                boolean mDestroyed, mDesktopPrepared;
                int mDesktopDisplayId = 7, mMouseBridgeSuspendedDisplayId = -1;
                int updates;
                void updateInputBridges() { updates++; }
                public static void verify() {
                    Fixture f = new Fixture();
                    f.onDesktopPrepared(8);
                    check(f.updates == 0, "foreign preparation enabled input");
                    f.onDesktopPrepared(7);
                    f.onDesktopPrepared(7);
                    check(f.mDesktopPrepared && f.updates == 1, "preparation restarted input");
                    f.releaseForSessionClose(8);
                    check(f.updates == 1, "foreign close stopped input");
                    f.releaseForSessionClose(7);
                    check(!f.mDesktopPrepared && f.updates == 2, "close did not stop input");
                    f.onDesktopPrepared(7);
                    check(!f.mDesktopPrepared && f.updates == 2, "late readiness reopened input");
                    f.mDesktopDisplayId = -1;
                    f.clearCompletedMouseBridgeSuspension(-1);
                    f.mDesktopDisplayId = 7;
                    f.onDesktopPrepared(7);
                    check(f.mDesktopPrepared && f.updates == 3, "next session cannot acquire input");
                    f.mDestroyed = true;
                    f.releaseForSessionClose(7);
                    check(f.updates == 3, "destroyed runtime accepted close");
                }
                """ + RuntimeSourceFixture.methods("RuntimeDesktopInputCoordinator",
                        "onDesktopPrepared", "releaseForSessionClose",
                        "isActiveDesktopDisplay", "clearCompletedMouseBridgeSuspension"));
    }

    @Test
    public void stoppedPointerReleasesRoutingBeforeDeviceDestruction() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display { static final int DEFAULT_DISPLAY = 0; }
                static final List<String> events = new ArrayList<>();
                static class Mouse {
                    boolean ready = true;
                    void start() { events.add("start"); }
                    boolean isReady() { return ready; }
                    void stop() { events.add("stop"); ready = false; }
                }
                final Mouse mMouseBridge = new Mouse();
                void reconcileRouting(boolean enabled, int display, boolean keyboard) {
                    events.add(enabled ? "route" : "unroute");
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    f.reconcile(true, 7, true, 7);
                    check(events.equals(List.of("unroute", "stop")),
                            "suspended but ready mouse kept its routes: " + events);
                    events.clear();
                    f.reconcile(true, 7, true, -1);
                    check(events.equals(List.of("start", "unroute")),
                            "capture routed before native readiness");
                    events.clear();
                    f.mMouseBridge.ready = true;
                    f.reconcile(true, 7, true, -1);
                    check(events.equals(List.of("start", "route")), "ready pointer was not routed");
                }
                """ + RuntimeSourceFixture.methods("DesktopInputRelaySession",
                        "reconcile", "shouldRunPointerBridge", "shouldRunRouting"));
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
