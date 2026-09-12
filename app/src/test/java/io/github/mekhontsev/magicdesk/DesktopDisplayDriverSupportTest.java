package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopDisplayDriverSupportTest {
    @Test public void automaticTouchpadDoesNotCoverAnotherDesktop() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String TAG = "test";
                static boolean phone, ready = true, created = true, enabled = true;
                static int opens;
                static class DesktopDisplayTarget { int workspaceDisplayId = 7; }
                enum DesktopSessionPolicy { USER }
                static class DesktopSessionController {
                    record ShowResult(boolean ready, boolean created) {}
                    static ShowResult show(Object target, Object policy) throws IOException {
                        return new ShowResult(Fixture.ready, Fixture.created);
                    }
                }
                static class DesktopDisplayDriver {
                    DesktopDisplayDriver features() { return this; }
                    boolean phoneTouchpad = true;
                    String kind() { return "wired"; }
                }
                static class DesktopDisplayDrivers {
                    static DesktopDisplayDriver forTarget(Object target) { return new DesktopDisplayDriver(); }
                }
                static class DesktopRuntimeBridge {
                    static boolean isLocalDesktopActiveOrStarting() { return phone; }
                }
                static class MagicDeskSettings {
                    boolean openTouchpadAutomatically = enabled;
                    static MagicDeskSettings load() { return new MagicDeskSettings(); }
                }
                static class PhoneTouchpadController { static void open(int id) { opens++; } }
                static class Log { static void w(Object... args) {} }
                static class CompatibilityDiagnostics { static void record(Object... args) {} }
                public static void verify() {
                    var target = new DesktopDisplayTarget();
                    phone = true;
                    showPrepared(target, DesktopSessionPolicy.USER);
                    check(opens == 0, "external startup covered the phone Desktop");
                    phone = false;
                    showPrepared(target, DesktopSessionPolicy.USER);
                    check(opens == 1, "ordinary phone lost configured automatic touchpad");
                    created = false;
                    showPrepared(target, DesktopSessionPolicy.USER);
                    check(opens == 1, "repeated Show opened the touchpad");
                    created = true; enabled = false;
                    showPrepared(target, DesktopSessionPolicy.USER);
                    check(opens == 1, "automatic touchpad preference was ignored");
                }
                """ + RuntimeSourceFixture.methods("DesktopDisplayDriverSupport", "showPrepared"));
    }
}
