package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ApplicationIconKeyTest {
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "00000000-0000-0000-0000-000000000002";

    @Test public void guestIdentityIsImmutableAndDoesNotAliasOtherEnvironments() {
        var a = ApplicationIconKey.guest("guest:" + A + ":editor.desktop");
        var b = ApplicationIconKey.guest("guest:" + B + ":editor.desktop");
        assertNotNull(a);
        assertNotEquals(a, b);
        assertEquals("guest:" + A + ":editor.desktop", a.guestReference());
        assertNull(ApplicationIconKey.guest("guest:ubuntu:editor.desktop"));
        assertNull(ApplicationIconKey.guest("guest:" + A + ":../editor.desktop"));
        assertNull(ApplicationIconKey.guest("guest:" + A + ":editor\n.desktop"));
        assertNull(ApplicationIconKey.guest("guest:" + A + ":"));
        assertEquals("sub-editor.desktop", ApplicationIconKey.desktopId("/usr/share/applications/sub/editor.desktop"));
    }

    @Test public void termuxArtworkUsesRecipeOwnerAndMissingMetadataDoesNotGuess() {
        var shortcut = new DesktopApplicationShortcut("Editor", "editor", "editor", null, "",
                DesktopLaunchMode.AUTO, false, DesktopExecBackend.TERMUX, false);
        assertNotEquals(ApplicationIconKey.of(shortcut, "com.termux"), ApplicationIconKey.of(shortcut, "other.termux"));
        assertNull(ApplicationIconKey.of(shortcut, ""));
        assertNull(ApplicationIconKey.of(null, "com.termux"));
        var bare = new DesktopApplicationShortcut("Editor", "", "editor", null, "",
                DesktopLaunchMode.AUTO, false, DesktopExecBackend.SHELL, false);
        assertNull(ApplicationIconKey.of(bare, "com.termux"));
    }

    @Test public void identicalGuestIdsInSeparateExecutorsDoNotShareArtwork() {
        String reference = "guest:" + A + ":editor.desktop";
        var user = ApplicationIconKey.guest(reference, DesktopExecBackend.APP);
        var shell = ApplicationIconKey.guest(reference, DesktopExecBackend.SHELL);
        assertNotEquals(user, shell);
        assertEquals(A, user.owner());
        assertEquals(A, shell.owner());
        assertEquals(reference, user.guestReference());
        assertEquals(reference, shell.guestReference());
    }
}
