package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GuestAppearanceTest {
    @Test public void prettyNameIsDisplayTextOnly() {
        assertEquals("Fedora Linux 43",GuestAppearance.displayName(" Fedora Linux 43 "));
        assertEquals("Alpine $(example)",GuestAppearance.displayName("Alpine $(example)"));
        assertEquals("",GuestAppearance.displayName("Linux\n(Shell)"));
        assertEquals("",GuestAppearance.displayName("Work\u202eevil"));
        assertEquals("",GuestAppearance.displayName("x".repeat(256)));
    }
}
