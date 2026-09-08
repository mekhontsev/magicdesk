package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public final class InputRoutingLeaseTest {
    @Test
    public void compositePortIsBoundOnceAndUnchangedInventoryDoesNotWrite() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.lease.reconcile("overlay:1", Set.of("bluetooth", "magicdesk-mouse"));
        final int calls = fixture.calls.size();
        fixture.lease.reconcile("overlay:1", Set.of("bluetooth", "magicdesk-mouse"));
        assertEquals(calls, fixture.calls.size());
        assertEquals(2, fixture.journal.size());
        fixture.lease.release();
        assertTrue(fixture.state.uniqueIds.isEmpty());
        assertTrue(fixture.journal.isEmpty());
    }

    @Test
    public void restoresBothPreviousAssociationMaps() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.state.runtimePorts.put("usb", 21);
        fixture.state.uniqueIds.put("usb", "local:phone");
        fixture.lease.reconcile("overlay:1", Set.of("usb"));
        assertFalse(fixture.state.runtimePorts.containsKey("usb"));
        assertEquals("overlay:1", fixture.state.uniqueIds.get("usb"));
        fixture.lease.release();
        assertEquals(Integer.valueOf(21), fixture.state.runtimePorts.get("usb"));
        assertEquals("local:phone", fixture.state.uniqueIds.get("usb"));
    }

    @Test
    public void hotUnplugReleasesOnlyRemovedPort() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.lease.reconcile("overlay:1", Set.of("first", "second"));
        fixture.lease.reconcile("overlay:1", Set.of("second"));
        assertFalse(fixture.state.uniqueIds.containsKey("first"));
        assertEquals("overlay:1", fixture.state.uniqueIds.get("second"));
        assertEquals(Set.of("second"), fixture.journal.keySet());
    }

    @Test
    public void changedExternalRouteIsNotOverwrittenAtClose() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.state.uniqueIds.put("usb", "local:phone");
        fixture.lease.reconcile("overlay:1", Set.of("usb"));
        fixture.state.uniqueIds.put("usb", "local:other");
        fixture.lease.release();
        assertEquals("local:other", fixture.state.uniqueIds.get("usb"));
        assertTrue(fixture.journal.isEmpty());
    }

    @Test
    public void failedJournalCannotChangeAndroid() {
        final Fixture fixture = new Fixture();
        fixture.failStorage = true;
        assertThrows(IOException.class,
                () -> fixture.lease.reconcile("overlay:1", Set.of("usb")));
        assertTrue(fixture.calls.isEmpty());
    }

    @Test
    public void lostAcquisitionAcknowledgementCanRecoverWithoutLiveDisplay() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.state.uniqueIds.put("usb", "local:phone");
        fixture.failAfterWrite = true;
        assertThrows(IOException.class,
                () -> fixture.lease.reconcile("overlay:1", Set.of("usb")));
        fixture.failAfterWrite = false;
        new InputRoutingLease(fixture, fixture).recover();
        assertEquals("local:phone", fixture.state.uniqueIds.get("usb"));
        assertTrue(fixture.journal.isEmpty());
    }

    @Test
    public void cleanupFailureRetainsOwnershipAndContinuesOtherPorts() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.lease.reconcile("overlay:1", Set.of("first", "second"));
        fixture.failPort = "first";
        assertThrows(IOException.class, fixture.lease::release);
        assertFalse(fixture.state.uniqueIds.containsKey("second"));
        assertEquals(Set.of("first"), fixture.journal.keySet());
        fixture.failPort = null;
        fixture.lease.release();
        assertTrue(fixture.journal.isEmpty());
    }

    @Test
    public void sameExistingRouteIsNotDeletedAtClose() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.state.uniqueIds.put("usb", "overlay:1");
        fixture.lease.reconcile("overlay:1", Set.of("usb"));
        fixture.lease.release();
        assertTrue(fixture.calls.isEmpty());
        assertEquals("overlay:1", fixture.state.uniqueIds.get("usb"));
    }

    @Test
    public void staticFirmwarePortFailsRatherThanClaimingAnIneffectiveRoute() {
        final Fixture fixture = new Fixture();
        fixture.state.staticPorts.put("usb", 0);
        assertThrows(IOException.class,
                () -> fixture.lease.reconcile("overlay:1", Set.of("usb")));
        assertTrue(fixture.calls.isEmpty());
    }

    private static final class Fixture implements InputRoutingLease.Api, InputRoutingLease.Storage {
        final FrameworkInputRoutingSnapshot state = new FrameworkInputRoutingSnapshot();
        final Map<String, InputRoutingLease.Entry> journal = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        final InputRoutingLease lease = new InputRoutingLease(this, this);
        boolean failStorage, failAfterWrite;
        String failPort;

        @Override
        public FrameworkInputRoutingSnapshot snapshot() {
            final FrameworkInputRoutingSnapshot copy = new FrameworkInputRoutingSnapshot();
            copy.staticPorts.putAll(state.staticPorts);
            copy.runtimePorts.putAll(state.runtimePorts);
            copy.uniqueIds.putAll(state.uniqueIds);
            return copy;
        }

        @Override
        public void setUniqueId(final String port, final String id) throws IOException {
            beforeWrite(port);
            if (id == null) state.uniqueIds.remove(port);
            else state.uniqueIds.put(port, id);
            if (failAfterWrite) throw new IOException("lost Binder reply");
        }

        @Override
        public void setDisplayPort(final String port, final Integer target) throws IOException {
            beforeWrite(port);
            if (target == null) state.runtimePorts.remove(port);
            else state.runtimePorts.put(port, target);
        }

        private void beforeWrite(final String port) throws IOException {
            assertTrue("journal must precede Binder write", journal.containsKey(port));
            if (port.equals(failPort)) throw new IOException("write failed");
            calls.add(port);
        }

        @Override
        public Map<String, InputRoutingLease.Entry> read() { return new LinkedHashMap<>(journal); }

        @Override
        public void write(final Map<String, InputRoutingLease.Entry> entries) throws IOException {
            if (failStorage) throw new IOException("storage unavailable");
            journal.clear();
            journal.putAll(entries);
        }
    }
}
