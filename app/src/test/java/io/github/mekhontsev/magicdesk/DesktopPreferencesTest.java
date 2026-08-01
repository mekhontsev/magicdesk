package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class DesktopPreferencesTest {
    @Test
    public void recentPackageMovesToFrontWithoutDuplicates() {
        final List<String> updated = DesktopPreferences.updateRecentPackages(
                Arrays.asList("app.one", "app.two", "app.three"),
                "app.two",
                4);

        assertEquals(
                Arrays.asList("app.two", "app.one", "app.three"),
                updated);
    }

    @Test
    public void recentPackagesAreBounded() {
        final List<String> updated = DesktopPreferences.updateRecentPackages(
                Arrays.asList("app.one", "app.two", "app.three"),
                "app.new",
                3);

        assertEquals(
                Arrays.asList("app.new", "app.one", "app.two"),
                updated);
    }
}
