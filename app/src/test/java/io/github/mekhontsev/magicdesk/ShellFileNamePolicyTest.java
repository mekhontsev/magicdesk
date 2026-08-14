package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class ShellFileNamePolicyTest {
    @Test
    public void trimsOrdinaryName() {
        assertEquals("notes.txt", ShellFileNamePolicy.validate(
                "  notes.txt  "));
    }

    @Test
    public void rejectsPathComponentsAndEmptyNames() {
        assertThrows(IllegalArgumentException.class,
                () -> ShellFileNamePolicy.validate("../notes.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> ShellFileNamePolicy.validate("folder/notes.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> ShellFileNamePolicy.validate("."));
        assertThrows(IllegalArgumentException.class,
                () -> ShellFileNamePolicy.validate("   "));
    }
}
