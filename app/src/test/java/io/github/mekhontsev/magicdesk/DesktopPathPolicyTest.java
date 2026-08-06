package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.file.Path;

public final class DesktopPathPolicyTest {
    @Test
    public void validateNameTrimsSafeName() {
        assertEquals("notes.txt", DesktopPathPolicy.validateName(
                "  notes.txt  "));
    }

    @Test
    public void validateNameRejectsPathComponents() {
        assertThrows(IllegalArgumentException.class,
                () -> DesktopPathPolicy.validateName("../notes.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopPathPolicy.validateName("folder/notes.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopPathPolicy.validateName("folder\\notes.txt"));
    }

    @Test
    public void resolveKeepsPathInsideDesktopRoot() {
        final Path root = Path.of("/storage/emulated/0/Desktop");
        assertEquals(
                root.resolve("notes.txt"),
                DesktopPathPolicy.resolve(root, "notes.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopPathPolicy.resolve(root, "../../escape.txt"));
    }
}
