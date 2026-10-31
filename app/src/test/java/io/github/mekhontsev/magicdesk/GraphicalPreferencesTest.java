package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GraphicalPreferencesTest {
    @Test public void settingIsAppPrivateAndCapturedValuesDoNotChange() throws Exception {
        String renderer = Files.readString(Path.of("../hosted-runtime/src/main/java/io/github/mekhontsev/magicdesk/hosted/HostedRenderer.java"))
                .replace("package io.github.mekhontsev.magicdesk.hosted;", "");
        RuntimeSourceFixture.verify(renderer + "static "
                + RuntimeSourceFixture.nestedClass("GraphicalPreferences", "GraphicalPreferences") + """
            static class R { static class string {
                static final int graphics_renderer_software = 1, graphics_renderer_automatic = 2;
            } }
            static class Context {
                static final int MODE_PRIVATE = 0;
                final Preferences preferences = new Preferences();
                Preferences getSharedPreferences(String name, int mode) {
                    check(name.equals("graphics") && mode == MODE_PRIVATE, "not app-private graphics settings");
                    return preferences;
                }
            }
            static class Preferences {
                final java.util.Map<String, String> values = new java.util.HashMap<>();
                String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
                Preferences edit() { return this; }
                Preferences putString(String key, String value) { values.put(key, value); return this; }
                void apply() { }
            }
            public static void verify() {
                var context = new Context();
                var first = GraphicalPreferences.renderer(context);
                check(first == HostedRenderer.AUTOMATIC && !first.softwareOnly(), "default renderer");
                GraphicalPreferences.setRenderer(context, HostedRenderer.SOFTWARE);
                var second = GraphicalPreferences.renderer(context);
                check(second.softwareOnly() && !first.softwareOnly(), "existing capture changed");
                check(context.preferences.values.get("renderer").equals("software"), "stable persisted id");
                GraphicalPreferences.setRenderer(context, HostedRenderer.AUTOMATIC);
                check(second.softwareOnly() && !GraphicalPreferences.renderer(context).softwareOnly(), "reset capture");
                check(HostedRenderer.fromId(second.id) == second, "server transport round trip");
                try { HostedRenderer.fromId("invalid"); throw new AssertionError("silent fallback"); }
                catch (IllegalArgumentException expected) { }
            }
            """);
    }
}
