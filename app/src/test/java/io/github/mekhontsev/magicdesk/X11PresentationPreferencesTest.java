package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class X11PresentationPreferencesTest {
    @Test public void applicationKeysSeparateTermuxPackagesAndDesktopEntries() {
        assertEquals("", X11PresentationPreferences.key("com.termux", null));
        assertEquals("", X11PresentationPreferences.key("com.termux", ""));
        assertNotEquals(X11PresentationPreferences.key("com.termux", "/apps/gimp.desktop"),
                X11PresentationPreferences.key("com.termux", "/apps/firefox.desktop"));
        assertNotEquals(X11PresentationPreferences.key("com.termux", "/apps/gimp.desktop"),
                X11PresentationPreferences.key("com.other.termux", "/apps/gimp.desktop"));
        assertEquals("com.termux|/apps/gimp.desktop",
                X11PresentationPreferences.key("com.termux", "/apps/gimp.desktop"));
    }
}
