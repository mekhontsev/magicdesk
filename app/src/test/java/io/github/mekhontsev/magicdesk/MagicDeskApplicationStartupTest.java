package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class MagicDeskApplicationStartupTest {
    @Test
    public void onlyThePrimaryProcessStartsRuntimeAndRecoversSharedState() throws Exception {
        RuntimeSourceFixture.verify("""
                static final List<String> calls = new ArrayList<>();
                static String processName;
                static class ApplicationInfo { String processName = "magicdesk"; }
                static class Context {
                    Context getApplicationContext() { return this; }
                    ApplicationInfo getApplicationInfo() { return new ApplicationInfo(); }
                }
                static class Application extends Context {
                    void onCreate() { calls.add("application"); }
                    static String getProcessName() { return processName; }
                }
                static class Process { static int myPid() { return 42; } }
                static class AndroidActivityResultStore {
                    static void releaseOrphanedPersistedUris(Context c) { calls.add("uri-recovery"); }
                }
                static class DesktopHomeStartupGuard {
                    static void relinquishStaleHome(Context c) { calls.add("home-recovery"); }
                }
                static class IntegrationPackage {
                    static void active() { calls.add("integrations"); }
                }
                static class ShellBackend { static void active() { calls.add("backend"); } }
                static class ShellPrivilegePolicy {
                    static void forceShell() { calls.add("identity-policy"); }
                }
                static class ShellAccess { static void initialize() { calls.add("service"); } }
                static class DesktopSetupStatus {
                    static void initialize(Context c) { calls.add("setup-status"); }
                }
                static class CompatibilityDiagnostics {
                    static void initialize(Context c) { calls.add("diagnostics"); }
                }
                static class DesktopAutomationEventJournal {
                    static void record(String type, String operation, boolean ok, String detail) {
                        check(type.equals("process") && operation.equals("started")
                                && ok && detail.equals("pid=42"), "process startup event");
                        calls.add("event");
                    }
                }
                static class App extends Application {
                    private static Context sApplicationContext;
                """ + RuntimeSourceFixture.methods("MagicDeskApplication",
                        "onCreate", "isPrimaryProcess", "applicationContext") + """
                }
                static void start(String name, List<String> expected) {
                    processName = name;
                    calls.clear();
                    App app = new App();
                    app.onCreate();
                    check(App.applicationContext() == app, "local context missing: " + name);
                    check(calls.equals(expected), "startup side effects in " + name + ": " + calls);
                }
                public static void verify() {
                    List<String> primary = List.of("application", "uri-recovery", "home-recovery",
                            "integrations", "backend", "identity-policy", "service", "setup-status", "diagnostics", "event");
                    start("magicdesk", primary);
                    for (String name : new String[] {"magicdesk:selftest",
                            "magicdesk:task_area_backstop", "magicdesk:another", "", null}) {
                        start(name, List.of("application"));
                    }
                    start("magicdesk", primary);
                    check(!App.isPrimaryProcess("magicdesk", null), "unknown owner accepted");
                }
                """);
    }
}
