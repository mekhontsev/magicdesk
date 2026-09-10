package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DisplayWindowingSessionTest {
    @Test
    public void phoneDoesNotReadOrWriteDisplayDefaults() throws Exception {
        final Fixture f = new Fixture();
        f.session.prepare(0);
        f.session.release(0);
        assertEquals(0, f.reads);
        assertTrue(f.writes.isEmpty());
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void alreadyFreeformDoesNotAcquireAnOverride() throws Exception {
        final Fixture f = new Fixture();
        f.display(2, "monitor", 5, false);
        f.session.prepare(2);
        final int reads = f.reads;
        f.session.prepare(2);
        f.session.release(2);
        assertEquals(reads, f.reads);
        assertTrue(f.writes.isEmpty());
        assertEquals(0, f.storageWrites);
    }

    @Test
    public void capturesOnceAndRestoresOnClose() throws Exception {
        final Fixture f = new Fixture();
        f.session.prepare(2);
        final int reads = f.reads;
        f.session.prepare(2);
        assertEquals(reads, f.reads);
        assertEquals(1, f.pending.get("monitor").mode);
        f.session.release(2);
        f.session.release(2);
        assertEquals(List.of("2=5", "2=1"), f.writes);
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void freeformWithoutSecondaryHomeStillAcquiresDecorations() throws Exception {
        final Fixture f = new Fixture();
        f.display(2, "monitor", 5, false, false);
        f.session.prepare(2);
        assertFalse(f.pending.get("monitor").systemDecorations);
        assertTrue(f.displays.get(2).systemDecorations);
        final int reads = f.reads;
        f.session.prepare(2);
        assertEquals(reads, f.reads);
        f.session.release(2);
        assertEquals(5, f.displays.get(2).mode);
        assertFalse(f.displays.get(2).systemDecorations);
        assertTrue(f.pending.isEmpty());
        assertEquals(List.of("2=5", "2=5"), f.writes);
    }

    @Test
    public void restartRestoresModeAndDecorationsTogether() throws Exception {
        final Fixture f = new Fixture();
        f.display(2, "monitor", 1, false, false);
        f.session.prepare(2);
        assertEquals(5, f.displays.get(2).mode);
        assertTrue(f.displays.get(2).systemDecorations);
        new DisplayWindowingSession(f, f).recover();
        assertEquals(1, f.displays.get(2).mode);
        assertFalse(f.displays.get(2).systemDecorations);
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void decorationsRestoreWithoutUndoingAnotherOwnersMode() throws Exception {
        final Fixture f = new Fixture();
        f.display(2, "monitor", 1, false, false);
        f.session.prepare(2);
        f.display(2, "monitor", 6, false, true);
        f.session.release(2);
        assertEquals(6, f.displays.get(2).mode);
        assertFalse(f.displays.get(2).systemDecorations);
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void unownedDecorationsAreNotReenabledAfterExternalChange() throws Exception {
        final Fixture f = new Fixture();
        f.session.prepare(2);
        f.display(2, "monitor", 5, false, false);
        f.session.release(2);
        assertEquals(1, f.displays.get(2).mode);
        assertFalse(f.displays.get(2).systemDecorations);
    }

    @Test
    public void refusedDecorationsAfterModeWriteRestoresPartialPreparation() throws Exception {
        final Fixture f = new Fixture();
        f.display(2, "monitor", 1, false, false);
        f.failAfterModeWrite = true;
        assertThrows(IOException.class, () -> f.session.prepare(2));
        assertEquals(5, f.displays.get(2).mode);
        assertFalse(f.displays.get(2).systemDecorations);
        f.session.release(2);
        assertEquals(1, f.displays.get(2).mode);
        assertFalse(f.displays.get(2).systemDecorations);
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void decorationsRestoreByStableIdentityAfterReconnect() throws Exception {
        final Fixture f = new Fixture();
        f.display(2, "monitor", 5, false, false);
        f.session.prepare(2);
        f.displays.remove(2);
        f.session.release(2);
        assertEquals(1, f.pending.size());
        f.display(2, "other-monitor", 5, false, true);
        f.display(7, "monitor", 5, false, true);
        f.session.recover();
        assertTrue(f.displays.get(2).systemDecorations);
        assertFalse(f.displays.get(7).systemDecorations);
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void recoveryDoesNotUndoAnActiveSession() throws Exception {
        final Fixture f = new Fixture();
        f.session.prepare(2);
        f.session.recover();
        assertEquals(List.of("2=5"), f.writes);
        assertEquals(1, f.pending.size());
        assertThrows(IOException.class, () -> f.session.prepare(3));
    }

    @Test
    public void anotherOwnersModeIsPreserved() throws Exception {
        final Fixture f = new Fixture();
        f.session.prepare(2);
        f.display(2, "monitor", 6, false);
        f.session.release(2);
        assertEquals(List.of("2=5"), f.writes);
        assertEquals(6, f.displays.get(2).mode);
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void storageFailurePreventsMutation() {
        final Fixture f = new Fixture();
        f.failStorage = true;
        assertThrows(IOException.class, () -> f.session.prepare(2));
        assertTrue(f.writes.isEmpty());
    }

    @Test
    public void failedWriteAcknowledgementCanBeRolledBack() throws Exception {
        final Fixture f = new Fixture();
        f.failAfterWrite = true;
        assertThrows(IOException.class, () -> f.session.prepare(2));
        f.session.release(2);
        assertEquals(List.of("2=5", "2=1"), f.writes);
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void refusedWriteDoesNotTurnIntoSuccessfulPreparation() throws Exception {
        final Fixture f = new Fixture();
        f.failBeforeWrite = true;
        assertThrows(IOException.class, () -> f.session.prepare(2));
        f.session.release(2);
        assertTrue(f.writes.isEmpty());
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void restorationFailureRemainsRecoverable() throws Exception {
        final Fixture f = new Fixture();
        f.session.prepare(2);
        f.failBeforeWrite = true;
        assertThrows(IOException.class, () -> f.session.release(2));
        assertEquals(1, f.pending.size());
        f.session.recover();
        assertEquals(List.of("2=5", "2=1"), f.writes);
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void processRestartRestoresPersistedEffectiveMode() throws Exception {
        final Fixture f = new Fixture();
        f.session.prepare(2);
        new DisplayWindowingSession(f, f).recover();
        assertEquals(List.of("2=5", "2=1"), f.writes);
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void reconnectUsesStableIdentityNotOldDisplayId() throws Exception {
        final Fixture f = new Fixture();
        f.session.prepare(2);
        f.displays.remove(2);
        f.session.release(2);
        f.session.recover();
        assertEquals(1, f.pending.size());
        f.display(2, "different-monitor", 5, false);
        f.display(7, "monitor", 5, false);
        f.session.recover();
        assertEquals(List.of("2=5", "7=1"), f.writes);
        assertEquals(5, f.displays.get(2).mode);
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void newSessionFirstRestoresInterruptedSessionBaseline() throws Exception {
        final Fixture f = new Fixture();
        f.session.prepare(2);
        final DisplayWindowingSession restarted = new DisplayWindowingSession(f, f);
        restarted.prepare(2);
        restarted.release(2);
        assertEquals(List.of("2=5", "2=1", "2=5", "2=1"), f.writes);
    }

    @Test
    public void removedVirtualDisplayNeedsNoDeferredRestoration() throws Exception {
        final Fixture f = new Fixture();
        f.display(2, "virtual", 1, true);
        f.session.prepare(2);
        f.displays.remove(2);
        f.session.release(2);
        assertTrue(f.pending.isEmpty());
        assertEquals(List.of("2=5"), f.writes);
    }

    @Test
    public void survivingVirtualDisplayIsRestoredAfterProcessRestart() throws Exception {
        final Fixture f = new Fixture();
        f.display(2, "virtual", 1, true);
        f.session.prepare(2);
        new DisplayWindowingSession(f, f).recover();
        assertEquals(List.of("2=5", "2=1"), f.writes);
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void missingVirtualDisplayIsForgottenAfterProcessRestart() throws Exception {
        final Fixture f = new Fixture();
        f.display(2, "virtual", 1, true);
        f.session.prepare(2);
        f.displays.remove(2);
        new DisplayWindowingSession(f, f).recover();
        assertTrue(f.pending.isEmpty());
    }

    @Test
    public void missingOrUnknownDisplayIsNotAssumedFullscreen() {
        final Fixture f = new Fixture();
        assertThrows(IOException.class, () -> f.session.prepare(7));
        f.display(2, "monitor", 0, false);
        assertThrows(IOException.class, () -> f.session.prepare(2));
        assertTrue(f.writes.isEmpty());
    }

    private static final class Fixture
            implements DisplayWindowingSession.Api, DisplayWindowingSession.Storage {
        final Map<Integer, DisplayWindowingSnapshot> displays = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        Map<String, DisplayWindowingSnapshot> pending = new LinkedHashMap<>();
        final DisplayWindowingSession session = new DisplayWindowingSession(this, this);
        int reads;
        int storageWrites;
        boolean failStorage;
        boolean failBeforeWrite;
        boolean failAfterWrite;
        boolean failAfterModeWrite;

        Fixture() {
            display(0, "phone", 1, false);
            display(2, "monitor", 1, false);
        }

        void display(final int id, final String uniqueId, final int mode, final boolean virtual) {
            display(id, uniqueId, mode, virtual, true);
        }

        void display(final int id, final String uniqueId, final int mode, final boolean virtual,
                final boolean systemDecorations) {
            displays.put(id, new DisplayWindowingSnapshot(id, uniqueId, mode, virtual,
                    systemDecorations));
        }

        @Override
        public int[] displayIds() {
            return displays.keySet().stream().mapToInt(Integer::intValue).toArray();
        }

        @Override
        public DisplayWindowingSnapshot read(final int displayId) {
            reads++;
            return displays.get(displayId);
        }

        @Override
        public void set(final int displayId, final String uniqueId, final int mode,
                final boolean systemDecorations)
                throws IOException {
            if (failBeforeWrite) {
                failBeforeWrite = false;
                throw new IOException("write refused");
            }
            final DisplayWindowingSnapshot current = displays.get(displayId);
            assertEquals(current.uniqueId, uniqueId);
            assertTrue("restore ownership must be saved before the write",
                    pending.containsKey(uniqueId));
            if (failAfterModeWrite) {
                failAfterModeWrite = false;
                display(displayId, uniqueId, mode, current.virtual, current.systemDecorations);
                throw new IOException("decorations refused after mode write");
            }
            display(displayId, uniqueId, mode, current.virtual, systemDecorations);
            writes.add(displayId + "=" + mode);
            if (failAfterWrite) {
                failAfterWrite = false;
                throw new IOException("acknowledgement lost");
            }
        }

        @Override
        public Map<String, DisplayWindowingSnapshot> read() {
            return new LinkedHashMap<>(pending);
        }

        @Override
        public void write(final Map<String, DisplayWindowingSnapshot> value) throws IOException {
            if (failStorage) {
                throw new IOException("storage unavailable");
            }
            storageWrites++;
            pending = new LinkedHashMap<>(value);
        }
    }
}
