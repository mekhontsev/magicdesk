package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public final class HostedWindowOwnersTest {
    @Test public void onlyOneBorrowerConfirmsAndReleaseTransfersAuthority() {
        var owners = new HostedWindowOwners();
        Object first = new Object(), second = new Object();
        assertTrue(owners.claim(1, first));
        assertFalse(owners.claim(1, second));
        assertTrue(owners.claim(2, second));
        assertTrue(owners.release(first));
        assertTrue(owners.claim(1, second));
        owners.retain(List.of(2L));
        assertFalse(owners.owns(1, second));
        assertTrue(owners.owns(2, second));
        assertFalse(owners.release(first));
    }
}
