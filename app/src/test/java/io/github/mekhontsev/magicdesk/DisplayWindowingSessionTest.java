package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        Fixture() {
            display(0, "phone", 1, false);
            display(2, "monitor", 1, false);
        }

        void display(final int id, final String uniqueId, final int mode, final boolean virtual) {
            displays.put(id, new DisplayWindowingSnapshot(id, uniqueId, mode, virtual));
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
        public void set(final int displayId, final String uniqueId, final int mode)
                throws IOException {
            if (failBeforeWrite) {
                failBeforeWrite = false;
                throw new IOException("write refused");
            }
            final DisplayWindowingSnapshot current = displays.get(displayId);
            assertEquals(current.uniqueId, uniqueId);
            assertTrue("restore ownership must be saved before the write",
                    pending.containsKey(uniqueId));
            display(displayId, uniqueId, mode, current.virtual);
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

    @Test public void concurrentDisplayDefaultsAreReleasedIndependently() throws Exception {
        final var f = new Fixture();
        f.display(3, "virtual", 6, true);
        f.session.prepare(2);
        f.session.prepare(3);
        f.session.recover();
        assertEquals(List.of("2=5", "3=5"), f.writes);
        f.session.release(2);
        assertEquals(1, f.displays.get(2).mode);
        assertEquals(5, f.displays.get(3).mode);
        assertEquals(Set.of("virtual"), f.pending.keySet());
        f.session.recover();
        assertEquals(5, f.displays.get(3).mode);
        f.session.release(3);
        assertEquals(6, f.displays.get(3).mode);
        assertTrue(f.pending.isEmpty());
    }
}
