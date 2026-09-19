package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class NubiaProjectionPackageLeaseTest {
    private static final class Backend implements NubiaProjectionPackageLease.Backend {
        int state;
        Integer restore;
        boolean denyWrite;
        boolean denyJournal;
        final List<String> events = new ArrayList<>();
        @Override public int readState() { return state; }
        @Override public Integer readRestoreState() { return restore; }
        @Override public void writeState(int value) throws IOException {
            events.add("write:" + value);
            if (denyWrite) { throw new IOException("denied"); }
            state = value;
        }
        @Override public void saveRestoreState(Integer value) throws IOException {
            events.add("journal:" + value);
            if (denyJournal) { throw new IOException("storage unavailable"); }
            restore = value;
        }
    }

    @Test public void journalsBeforeDisablingAndRestoresExactState() throws Exception {
        for (int initial : new int[]{0, 1}) {
            Backend b = new Backend(); b.state = initial;
            NubiaProjectionPackageLease lease = new NubiaProjectionPackageLease(b);
            lease.acquire(); lease.acquire();
            assertEquals(List.of("journal:" + initial, "write:3"), b.events);
            assertEquals(3, b.state);
            lease.release(); lease.release();
            assertEquals(initial, b.state);
            assertNull(b.restore);
            assertEquals(List.of("journal:" + initial, "write:3", "write:" + initial, "journal:null"), b.events);
        }
    }

    @Test public void neverEnablesAnAlreadyDisabledPackage() throws Exception {
        for (int state : new int[]{2, 3, 4}) {
            Backend b = new Backend(); b.state = state;
            NubiaProjectionPackageLease lease = new NubiaProjectionPackageLease(b);
            lease.acquire(); lease.release();
            assertEquals(state, b.state);
            assertTrue(b.events.isEmpty());
        }
    }

    @Test public void newProcessCanRecoverAndDoesNotOverwriteUserChanges() throws Exception {
        Backend b = new Backend();
        new NubiaProjectionPackageLease(b).acquire();
        new NubiaProjectionPackageLease(b).release();
        assertEquals(0, b.state);
        new NubiaProjectionPackageLease(b).acquire();
        b.state = 2;
        new NubiaProjectionPackageLease(b).release();
        assertEquals(2, b.state);
        assertNull(b.restore);
    }

    @Test public void failedRestorationKeepsJournalForRecovery() throws Exception {
        Backend b = new Backend();
        NubiaProjectionPackageLease lease = new NubiaProjectionPackageLease(b);
        lease.acquire(); b.denyWrite = true;
        assertThrows(IOException.class, lease::release);
        assertEquals(Integer.valueOf(0), b.restore);
        b.denyWrite = false;
        lease.release();
        assertEquals(0, b.state);
        assertNull(b.restore);
    }

    @Test public void cannotDisableWithoutDurableJournal() {
        Backend b = new Backend(); b.denyJournal = true;
        assertThrows(IOException.class, () -> new NubiaProjectionPackageLease(b).acquire());
        assertEquals(0, b.state);
        assertFalse(b.events.contains("write:3"));
    }
}
