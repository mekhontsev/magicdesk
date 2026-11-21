package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TaskScaleSynchronizationTest {
    private static final class Fixture implements PlatformWindowingDriver.TaskScalePeer,
            TaskScaleSynchronization.Geometry {
        float peer = .75f, wm = .75f;
        int writes, commits, validations, reads, readsUntilAck;
        boolean stale, staleAfterRequest, denied, failBeforeCommit, failAfterCommit, disappear;
        boolean delayed, neverAcknowledge;
        Float pending;

        @Override public float read() {
            reads++;
            if (pending != null && !neverAcknowledge && --readsUntilAck <= 0) { peer = pending; pending = null; }
            return peer;
        }
        @Override public void request(float value) {
            writes++;
            if (denied) throw new SecurityException("denied");
            if (delayed || neverAcknowledge) { pending = value; readsUntilAck = 2; }
            else peer = value;
        }
        @Override public boolean isCurrent() {
            validations++;
            return !stale && !(staleAfterRequest && writes > 0);
        }
        @Override public void commit() {
            commits++;
            if (failBeforeCommit) throw new IllegalStateException("rejected");
            wm = .5f;
            if (failAfterCommit) throw new IllegalStateException("verification failed");
        }
        @Override public float committedScale() { return disappear ? Float.NaN : wm; }
    }

    @Test public void delayedOneWayAcknowledgementPrecedesGeometry() throws Exception {
        Fixture f = new Fixture(); f.delayed = true;
        assertTrue(TaskScaleSynchronization.apply(f, .75f, .5f, f));
        assertEquals(.5f, f.peer, 0); assertEquals(.5f, f.wm, 0);
        assertEquals(1, f.writes); assertEquals(1, f.commits); assertEquals(2, f.validations);
        assertTrue(f.reads >= 4);
    }

    @Test public void supersededRequestDoesNotMutateAndLateSupersessionCompensates() throws Exception {
        Fixture f = new Fixture(); f.stale = true;
        assertFalse(TaskScaleSynchronization.apply(f, .75f, .5f, f));
        assertEquals(0, f.reads); assertEquals(0, f.writes); assertEquals(0, f.commits);
        f.stale = false; f.staleAfterRequest = true;
        assertFalse(TaskScaleSynchronization.apply(f, .75f, .5f, f));
        assertEquals(2, f.writes); assertEquals(0, f.commits); assertEquals(.75f, f.peer, 0);
    }

    @Test public void rejectionRestoresPeerButAppliedGeometryIsNotRolledBackBlindly() {
        Fixture before = new Fixture(); before.failBeforeCommit = true;
        assertThrows(IllegalStateException.class, () -> TaskScaleSynchronization.apply(before, .75f, .5f, before));
        assertEquals(.75f, before.peer, 0); assertEquals(2, before.writes);
        Fixture after = new Fixture(); after.failAfterCommit = true;
        assertThrows(IllegalStateException.class, () -> TaskScaleSynchronization.apply(after, .75f, .5f, after));
        assertEquals(.5f, after.peer, 0); assertEquals(.5f, after.wm, 0);
    }

    @Test public void missingTaskCannotReceiveCompensation() {
        Fixture f = new Fixture(); f.failAfterCommit = true; f.disappear = true;
        assertThrows(IllegalStateException.class, () -> TaskScaleSynchronization.apply(f, .75f, .5f, f));
        assertEquals(1, f.writes);
    }

    @Test public void denialAndDivergenceDoNotCommitGeometry() {
        Fixture denied = new Fixture(); denied.denied = true;
        var error = assertThrows(SecurityException.class,
                () -> TaskScaleSynchronization.apply(denied, .75f, .5f, denied));
        assertEquals(0, denied.commits); assertEquals(1, error.getSuppressed().length);
        Fixture diverged = new Fixture(); diverged.peer = .6f;
        assertThrows(IllegalStateException.class,
                () -> TaskScaleSynchronization.apply(diverged, .75f, .5f, diverged));
        assertEquals(0, diverged.commits); assertEquals(0, diverged.writes);
    }

    @Test public void timeoutIsFailureNotPermissionToSubmit() {
        Fixture f = new Fixture(); f.neverAcknowledge = true;
        assertThrows(IllegalStateException.class, () -> TaskScaleSynchronization.apply(f, .75f, .5f, f));
        assertEquals(0, f.commits); assertEquals(2, f.writes);
        assertEquals(.75f, f.pending, 0);
    }

    @Test public void unchangedScaleHasNoWriteOrWait() throws Exception {
        Fixture f = new Fixture(); f.peer = f.wm = .5f;
        assertTrue(TaskScaleSynchronization.apply(f, .5f, .5f, f));
        assertEquals(0, f.writes); assertEquals(1, f.commits);
    }
}
