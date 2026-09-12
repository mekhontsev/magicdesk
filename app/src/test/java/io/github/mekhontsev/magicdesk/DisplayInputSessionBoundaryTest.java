package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

/** Desktop and explicit display control share one input ownership policy. */
public final class DisplayInputSessionBoundaryTest {
    @Test public void displayExistenceIncludesPhoneAndRejectsMissingTargets() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Displays {
                    Object getDisplay(int id) { return id == 0 || id == 7 ? this : null; }
                }
                Displays mDisplayManager = new Displays();
                public static void verify() {
                    Fixture f = new Fixture();
                    check(f.hasDisplay(0), "phone display was excluded");
                    check(f.hasDisplay(7), "external display was excluded");
                    check(!f.hasDisplay(-1), "invalid display exists");
                    check(!f.hasDisplay(8), "disconnected display exists");
                    f.mDisplayManager = null;
                    check(!f.hasDisplay(0), "missing display service counted as ready");
                }
                """ + RuntimeSourceFixture.methods("RuntimeDisplayCoordinator", "hasDisplay"));
    }

    @Test public void ordinaryControlDoesNotRequireDesktop() {
        var target = new DisplayInputTarget();
        target.select(7);
        assertEquals(7, target.readyTarget());
        assertFalse(target.desktopShortcuts());
        target.release(7);
        assertEquals(-1, target.readyTarget());
    }

    @Test public void desktopWaitsForPreparationAndRejectsLateReadinessAfterClose() {
        var target = new DisplayInputTarget();
        target.desktop(7);
        assertEquals(-1, target.readyTarget());
        target.prepared(8);
        assertEquals(-1, target.readyTarget());
        target.prepared(7);
        assertEquals(7, target.readyTarget());
        assertTrue(target.desktopShortcuts());
        assertFalse(target.release(8));
        assertEquals(7, target.readyTarget());
        target.release(7);
        target.prepared(7);
        assertEquals(-1, target.readyTarget());
        target.desktop(-1);
        target.desktop(7);
        assertEquals(-1, target.readyTarget());
        target.prepared(7);
        assertEquals(7, target.readyTarget());
    }

    @Test public void closingDesktopPreservesLaterManualSelectionOnAnotherDisplay() {
        var target = new DisplayInputTarget();
        target.desktop(7);
        target.prepared(7);
        target.select(8);
        assertFalse(target.desktopShortcuts());
        assertFalse(target.release(7));
        target.desktop(-1);
        assertEquals(8, target.readyTarget());
        assertTrue(target.release(8));
        assertEquals(-1, target.readyTarget());
    }

    @Test public void newDesktopReplacesManualControlButOnlyAfterPreparation() {
        var target = new DisplayInputTarget();
        target.select(8);
        target.desktop(7);
        assertEquals(-1, target.readyTarget());
        target.prepared(7);
        assertEquals(7, target.readyTarget());
        target.select(-1);
        target.desktop(7);
        target.prepared(7);
        assertEquals(-1, target.readyTarget());
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
