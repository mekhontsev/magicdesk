package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GraphicalPresentationPreferencesTest {
    @Test public void applicationKeysSeparateTermuxPackagesAndDesktopEntries() {
        assertEquals("", GraphicalPresentationPreferences.key("com.termux", null));
        assertEquals("", GraphicalPresentationPreferences.key("com.termux", ""));
        assertNotEquals(GraphicalPresentationPreferences.key("com.termux", "/apps/gimp.desktop"),
                GraphicalPresentationPreferences.key("com.termux", "/apps/firefox.desktop"));
        assertNotEquals(GraphicalPresentationPreferences.key("com.termux", "/apps/gimp.desktop"),
                GraphicalPresentationPreferences.key("com.other.termux", "/apps/gimp.desktop"));
        assertNotEquals(GraphicalPresentationPreferences.key("shell:0", "/apps/gimp.desktop"),
                GraphicalPresentationPreferences.key("shell:2000", "/apps/gimp.desktop"));
        assertEquals("com.termux|/apps/gimp.desktop",
                GraphicalPresentationPreferences.key("com.termux", "/apps/gimp.desktop"));
    }

    @Test public void persistenceAndLiveUpdatesUseOneProtocolNeutralProfile() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", "static "
                + RuntimeSourceFixture.nestedClass("GraphicalPresentationPreferences", "GraphicalPresentationPreferences")
                + "static " + RuntimeSourceFixture.nestedClass("AppPresentationProfile", "AppPresentationProfile") + """
            static class Context {
                static final int MODE_PRIVATE = 0;
                final Preferences preferences = new Preferences();
                Preferences getSharedPreferences(String name, int mode) {
                    check(name.equals("graphics_presentation"), "protocol-specific storage");
                    return preferences;
                }
            }
            static class Preferences {
                final java.util.Map<String, Integer> values = new java.util.HashMap<>();
                int getInt(String key, int fallback) { return values.getOrDefault(key, fallback); }
                Preferences edit() { return this; }
                void remove(String key) { values.remove(key); }
                void putInt(String key, int value) { values.put(key, value); }
                void apply() { }
            }
            static class GraphicalSessions {
                static final java.util.List<Session> sessions = new java.util.ArrayList<>();
                static java.util.List<Session> list() { return sessions; }
                static class Session {
                    final String key;
                    int scale = 100;
                    boolean closed;
                    Session(String key) { this.key = key; sessions.add(this); }
                    String presentationKey() { return key; }
                    boolean stopped() { return closed; }
                    void setScale(int value) {
                        if (!AppPresentationProfile.isValidScale(value)) throw new IllegalArgumentException();
                        scale = value;
                    }
                }
            }
            public static void verify() {
                var context = new Context();
                var x11 = new GraphicalSessions.Session("com.termux|/app.desktop");
                var wayland = new GraphicalSessions.Session(x11.key);
                var otherPackage = new GraphicalSessions.Session("com.other|/app.desktop");
                var otherApp = new GraphicalSessions.Session("com.termux|/other.desktop");
                var closed = new GraphicalSessions.Session(x11.key); closed.closed = true;
                GraphicalPresentationPreferences.save(context, wayland, 150);
                check(x11.scale == 150 && wayland.scale == 150, "both backends update");
                check(otherApp.scale == 100 && otherPackage.scale == 100 && closed.scale == 100, "scope and lifecycle isolation");
                check(GraphicalPresentationPreferences.load(context, x11.key) == 150, "new launch retains profile");
                GraphicalPresentationPreferences.save(context, x11.key, 100);
                check(x11.scale == 100 && wayland.scale == 100, "automatic reset propagated");
                check(!context.preferences.values.containsKey(x11.key), "reset removes override");
                context.preferences.values.put(x11.key, 900);
                check(GraphicalPresentationPreferences.load(context, x11.key) == 100, "corrupt preference fallback");
                var adHoc = new GraphicalSessions.Session("");
                var unrelated = new GraphicalSessions.Session("");
                GraphicalPresentationPreferences.save(context, adHoc, 50);
                check(adHoc.scale == 50 && unrelated.scale == 100, "ad-hoc session isolation");
                check(GraphicalPresentationPreferences.load(context, "") == 100, "ad-hoc is never persisted");
                try { GraphicalPresentationPreferences.save(context, adHoc, 201); throw new AssertionError(); }
                catch (IllegalArgumentException expected) { }
                try { GraphicalPresentationPreferences.save(context, closed, 150); throw new AssertionError(); }
                catch (IllegalArgumentException expected) { }
            }
            """);
    }
}
