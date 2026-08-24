package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopMimeTypesTest {
    @Test
    public void exactAndWildcardTypesMatchCaseInsensitively() {
        final DesktopMimeTypes types = DesktopMimeTypes.parse(
                "Text/Plain;image/*;application/json;");

        assertTrue(types.matches("text/plain"));
        assertTrue(types.matches("IMAGE/PNG"));
        assertTrue(types.matches("application/json"));
        assertFalse(types.matches("application/pdf"));
        assertEquals(
                "text/plain;image/*;application/json;",
                types.encode());
    }

    @Test
    public void globalWildcardMatchesKnownMimeTypes() {
        final DesktopMimeTypes types = DesktopMimeTypes.parse("*/*;");

        assertTrue(types.matches("text/plain"));
        assertTrue(types.matches("application/octet-stream"));
    }

    @Test
    public void malformedTypesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DesktopMimeTypes.parse("text;"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DesktopMimeTypes.parse("*/plain;"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DesktopMimeTypes.parse("text/plain value;"));
    }

    @Test
    public void openWithRequiresMimeAndFileArgumentContracts() {
        assertTrue(DesktopApplicationRepository.accepts(
                shortcut("viewer %f", "text/plain;"),
                "text/plain"));
        assertFalse(DesktopApplicationRepository.accepts(
                shortcut("viewer", "text/plain;"),
                "text/plain"));
        assertFalse(DesktopApplicationRepository.accepts(
                shortcut("viewer %f", ""),
                "text/plain"));
        assertFalse(DesktopApplicationRepository.accepts(
                shortcut("viewer %f", "image/*;"),
                "text/plain"));
    }

    private static DesktopApplicationShortcut shortcut(
            final String exec,
            final String mimeTypes) {
        return new DesktopApplicationShortcut(
                "Viewer",
                "",
                exec,
                null,
                "",
                DesktopLaunchMode.AUTO,
                false,
                DesktopExecBackend.SHELL,
                false,
                "",
                DesktopMimeTypes.parse(mimeTypes));
    }
}
