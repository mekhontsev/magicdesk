package io.github.mekhontsev.magicdesk.hosted;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public final class SharedFileNamespaceTest {
    @Before public void requiresPosixFileSystem() {
        // Path and File use the host filesystem to interpret Android's absolute Unix paths.
        assumeTrue(java.io.File.separatorChar == '/');
    }

    @Test public void mapsOnlyTheExplicitExchangeDirectory() throws Exception {
        var paths = new SharedFileNamespace("/private/session/content", "/tmp/magicdesk-x11/content");
        assertEquals("/private/session/content/sub/a b.txt", paths.hostPath("/tmp/magicdesk-x11/content/sub/a b.txt"));
        assertEquals("file:///tmp/magicdesk-x11/content/sub/a%20b.txt", paths.guestUri("/private/session/content/sub/a b.txt"));
        for (String value : new String[]{"/etc/shadow", "/private/session/Xauthority", "/private/session/content-other/a",
                "/tmp/magicdesk-x11/content/../../secret", "/private/session/content"})
            assertThrows(java.io.IOException.class, () -> paths.hostPath(value));
    }

    @Test public void descriptorResolutionCannotEscapeThroughASymlink() {
        var paths = new SharedFileNamespace("/private/session/content", "/tmp/magicdesk-x11/content");
        assertThrows(java.io.IOException.class, () -> paths.requireInside("/private/app/preferences/secrets.xml"));
        assertThrows(java.io.IOException.class, () -> paths.requireInside("/private/session/content/../Xauthority"));
    }

    @Test public void protocolsHaveIndependentGuestMounts() throws Exception {
        var paths = new SharedFileNamespace("/private/session/content", "/tmp/magicdesk-wayland/content");
        assertEquals("file:///tmp/magicdesk-wayland/content/a.txt", paths.guestUri("/private/session/content/a.txt"));
        assertEquals("/private/session/content/a.txt", paths.hostPath("/tmp/magicdesk-wayland/content/a.txt"));
        assertThrows(java.io.IOException.class, () -> paths.hostPath("/tmp/magicdesk-x11/content/a.txt"));
    }
}
