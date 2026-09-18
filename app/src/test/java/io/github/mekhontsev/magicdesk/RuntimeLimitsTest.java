package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class RuntimeLimitsTest {
    @Test public void savedLimitsCannotChangeLiveIdentityOrCleanupPolicy() throws Exception {
        RuntimeSourceFixture.verify("""
                enum Access { ROOT, SHELL, APP_ONLY }
                record Values(Access access, boolean termux, boolean desktop) {}
                static final Values DEFAULT = new Values(Access.SHELL, true, true);
                static class Preferences {
                    Map<String, Object> values = new HashMap<>();
                    String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
                    boolean getBoolean(String key, boolean fallback) { return (boolean) values.getOrDefault(key, fallback); }
                    Preferences edit() { return this; }
                    Preferences putString(String key, String value) { values.put(key, value); return this; }
                    Preferences putBoolean(String key, boolean value) { values.put(key, value); return this; }
                    boolean commit() { return true; }
                }
                static class Context {
                    static int MODE_PRIVATE = 0;
                    Preferences preferences = new Preferences();
                    Preferences getSharedPreferences(String name, int mode) {
                        check(name.equals("runtime_limits"), "limits share shell-backed state");
                        return preferences;
                    }
                }
                static class MagicDeskApplication {
                    static Context context = new Context();
                    static Context applicationContext() { return context; }
                }
                static class ShellBackend {
                    static final Object INITIAL = new Object();
                    static Object configured = INITIAL;
                    static Object active() { return INITIAL; }
                    static Object configured(Context context) { return configured; }
                }
                """ + RuntimeSourceFixture.methods("RuntimeLimits", "active", "configured", "save", "restartRequired")
                        + RuntimeSourceFixture.nestedClass("RuntimeLimits", "Active") + """
                public static void verify() {
                    Context context = MagicDeskApplication.context;
                    check(active().equals(DEFAULT), "startup default is not shell");
                    check(!restartRequired(context), "fresh settings need restart");
                    Values changed = new Values(Access.APP_ONLY, false, false);
                    check(save(context, changed), "settings not saved");
                    check(configured(context).equals(changed), "configured limits not updated");
                    check(active().equals(DEFAULT), "live policy changed before cleanup");
                    check(restartRequired(context), "pending limits not reported");
                    save(context, DEFAULT);
                    check(!restartRequired(context), "reverted settings remain pending");
                    ShellBackend.configured = new Object();
                    check(restartRequired(context), "transport change not pending");
                }
                """);
    }

    @Test public void appOnlyNeverConstructsEitherPrivilegeTransport() throws Exception {
        RuntimeSourceFixture.verify("""
                static class RuntimeLimits {
                    static RuntimeLimits active() { return new RuntimeLimits(); }
                    boolean privilegedAllowed() { return false; }
                }
                static class ShellBackend {
                    static ShellBackend active() { throw new AssertionError("transport was selected"); }
                    boolean usesRoot() { throw new AssertionError("transport was inspected"); }
                }
                interface ShellServiceLauncher {}
                static class DisabledShellServiceLauncher implements ShellServiceLauncher {}
                static class ShellProcessLauncher implements ShellServiceLauncher {
                    ShellProcessLauncher(ShellBackend backend) { throw new AssertionError("root launcher constructed"); }
                }
                static class ShizukuServiceLauncher implements ShellServiceLauncher {
                    ShizukuServiceLauncher() { throw new AssertionError("Shizuku launcher constructed"); }
                }
                """ + RuntimeSourceFixture.nestedClass("ShellServiceLauncher", "Active") + """
                public static void verify() {
                    check(Active.INSTANCE instanceof DisabledShellServiceLauncher, "privilege launcher selected");
                }
                """);
    }
}
