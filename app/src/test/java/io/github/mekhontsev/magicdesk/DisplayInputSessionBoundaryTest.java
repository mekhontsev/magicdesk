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
        target.reconcile(java.util.Map.of(7, "first"));
        assertEquals(-1, target.readyTarget());
        target.prepared(8, "other");
        assertEquals(-1, target.readyTarget());
        target.prepared(7, "first");
        assertEquals(7, target.readyTarget());
        assertTrue(target.desktopShortcuts());
        assertFalse(target.release(8));
        assertEquals(7, target.readyTarget());
        target.release(7);
        target.prepared(7, "first");
        assertEquals(-1, target.readyTarget());
        target.reconcile(java.util.Map.of());
        target.reconcile(java.util.Map.of(7, "first"));
        assertEquals(-1, target.readyTarget());
        target.prepared(7, "first");
        assertEquals(7, target.readyTarget());
    }

    @Test public void closingDesktopPreservesLaterManualSelectionOnAnotherDisplay() {
        var target = new DisplayInputTarget();
        target.reconcile(java.util.Map.of(7, "first"));
        target.prepared(7, "first");
        target.select(8);
        assertFalse(target.desktopShortcuts());
        assertFalse(target.release(7));
        target.reconcile(java.util.Map.of());
        assertEquals(8, target.readyTarget());
        assertTrue(target.release(8));
        assertEquals(-1, target.readyTarget());
    }

    @Test public void newDesktopReplacesManualControlButOnlyAfterPreparation() {
        var target = new DisplayInputTarget();
        target.select(8);
        target.reconcile(java.util.Map.of(7, "first"));
        assertEquals(8, target.readyTarget());
        target.prepared(7, "first");
        assertEquals(7, target.readyTarget());
        target.select(-1);
        target.reconcile(java.util.Map.of(7, "first"));
        target.prepared(7, "first");
        assertEquals(-1, target.readyTarget());
    }

    @Test public void restorationReadinessIsLocalToItsWorkspace() throws Exception {
        RuntimeSourceFixture.verify("""
                static class DesktopSessionSnapshot {
                    int displayId = 7;
                    boolean restoreWorkspace = true;
                    DesktopSessionSnapshot policy() { return this; }
                    int activeWorkspaceDisplayId() { return displayId; }
                }
                final Object mLock = new Object();
                final Map<Integer, String> mRestores = new HashMap<>();
                public static void verify() {
                    Fixture f = new Fixture();
                    DesktopSessionSnapshot session = new DesktopSessionSnapshot();
                    check(f.isWorkspacePrepared(session), "empty workspace is not ready");
                    f.mRestores.put(8, "other");
                    check(f.isWorkspacePrepared(session), "another workspace blocked input");
                    f.mRestores.put(7, "current");
                    check(!f.isWorkspacePrepared(session), "unfinished restore accepted");
                    f.mRestores.remove(7);
                    check(f.isWorkspacePrepared(session), "completed restore still blocked");
                    session.restoreWorkspace = false;
                    f.mRestores.put(7, "current");
                    check(f.isWorkspacePrepared(session), "isolated test waited for user windows");
                }
                """ + RuntimeSourceFixture.methods("DesktopTaskParkingController", "isWorkspacePrepared"));
    }

}
