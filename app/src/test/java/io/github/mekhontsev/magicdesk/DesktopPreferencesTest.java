package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class DesktopPreferencesTest {
    private static AppReference app(final String name) {
        return new AppProfile(0, 0).reference(AppLaunchTarget.packageDefault(name));
    }

    @Test
    public void recentAppMovesToFrontWithoutDuplicates() {
        final List<AppReference> updated = DesktopPreferences.updateRecentApps(
                Arrays.asList(app("app.one"), app("app.two"), app("app.three")),
                app("app.two"),
                4);

        assertEquals(
                Arrays.asList(app("app.two"), app("app.one"), app("app.three")),
                updated);
    }

    @Test
    public void recentAppsAreBounded() {
        final List<AppReference> updated = DesktopPreferences.updateRecentApps(
                Arrays.asList(app("app.one"), app("app.two"), app("app.three")),
                app("app.new"),
                3);

        assertEquals(
                Arrays.asList(app("app.new"), app("app.one"), app("app.two")),
                updated);
    }
}
