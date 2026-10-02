package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class AutomationCommandLeasesTest {
    @Test public void closingOneOwnerRevokesOnlyItsClientsAndCleansOnce() throws Exception {
        try (var leases = new AutomationCommandLeases()) {
            var closed = new AtomicInteger(); var owner = new Socket(); var client = new Socket();
            var first = leases.create(owner, () -> true, closed::incrementAndGet);
            var second = leases.create(new Socket(), () -> true, () -> {});
            assertNotEquals(first.key, second.key);
            assertSame(first, leases.acquire(first.key, client));
            first.close(); first.close();
            assertTrue(owner.isClosed()); assertTrue(client.isClosed()); assertEquals(1, closed.get());
            assertThrows(IOException.class, first::requireActive);
            assertThrows(IOException.class, () -> leases.acquire(first.key, new Socket()));
            second.requireActive();
        }
    }
    @Test public void serviceReplacementRejectsExistingAndNewRequests() throws Exception {
        try (var leases = new AutomationCommandLeases()) {
            var valid = new AtomicBoolean(true);
            var lease = leases.create(new Socket(), valid::get, () -> {});
            lease.requireActive(); valid.set(false);
            assertThrows(IOException.class, lease::requireActive);
            assertThrows(IOException.class, () -> leases.acquire(lease.key, new Socket()));
        }
    }
    @Test public void lifetimeRegistryIsBoundedAndRuntimeClosureIsFinal() throws Exception {
        var leases = new AutomationCommandLeases();
        for (int i=0;i<32;i++) leases.create(new Socket(), () -> true, () -> {});
        assertThrows(IOException.class, () -> leases.create(new Socket(), () -> true, () -> {}));
        leases.close();
        assertThrows(IOException.class, () -> leases.create(new Socket(), () -> true, () -> {}));
    }
}
