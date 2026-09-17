package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ShellActivityLaunchScopeTest {
    @Test
    public void explicitLaunchWithStrippedFlagsIsNotAPhoneMigration() throws Exception {
        verify("""
                check(guard.findPhoneLaunchTarget(intent, "app") == task, "ordinary phone launch no longer migrates");
                try (var scope = ShellActivityLaunchScope.begin(identity)) {
                    check(guard.findPhoneLaunchTarget(intent, "app") == null, "intercepted our explicit launch");
                    Thread callback = new Thread(() -> check(
                            guard.findPhoneLaunchTarget(intent, "app") == null, "Binder thread lost provenance"));
                    callback.setUncaughtExceptionHandler((thread, failure) -> callbackFailure = failure);
                    callback.start(); callback.join();
                    check(callbackFailure == null, "cross-thread launch provenance failed");
                }
                check(guard.findPhoneLaunchTarget(intent, "app") == task, "scope leaked after completion");
                """);
    }

    @Test
    public void aliasesMatchButAnotherActivityOrPackageDoesNot() throws Exception {
        verify("""
                try (var scope = ShellActivityLaunchScope.begin(identity)) {
                    check(ShellActivityLaunchScope.isActive(new Intent(alias), "app"), "lost alias identity");
                    check(ShellActivityLaunchScope.isActive(intent, "app"), "lost resolved identity");
                    check(!ShellActivityLaunchScope.isActive(new Intent(new ComponentName("app", "Other")), "app"),
                            "exempted unrelated same-package activity");
                    check(!ShellActivityLaunchScope.isActive(new Intent(new ComponentName("other", "Main")), "other"),
                            "exempted another package");
                    check(!ShellActivityLaunchScope.isActive(null, "app"), "accepted missing intent");
                }
                """);
    }

    @Test
    public void failureAndNestedScopesReleaseOnlyTheirOwnLaunch() throws Exception {
        verify("""
                try (var outer = ShellActivityLaunchScope.begin(identity)) {
                    try (var inner = ShellActivityLaunchScope.begin(identity)) {
                        throw new IllegalStateException("launch aborted");
                    } catch (IllegalStateException expected) { }
                    check(ShellActivityLaunchScope.isActive(intent, "app"), "inner cleanup released outer scope");
                }
                check(!ShellActivityLaunchScope.isActive(intent, "app"), "failed scope leaked");
                var scope = ShellActivityLaunchScope.begin(identity);
                scope.close(); scope.close();
                check(!ShellActivityLaunchScope.isActive(intent, "app"), "close was not idempotent");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static Throwable callbackFailure;
                record ComponentName(String pkg, String name) {
                    String getPackageName() { return pkg; }
                }
                record Intent(ComponentName component) {
                    static final String ACTION_MAIN = "main", CATEGORY_LAUNCHER = "launcher";
                    ComponentName getComponent() { return component; }
                    String getPackage() { return component == null ? null : component.pkg; }
                    String getAction() { return ACTION_MAIN; }
                    Set<String> getCategories() { return Set.of(CATEGORY_LAUNCHER); }
                    int getFlags() { return 0; }
                }
                static class LaunchActivityIdentity {
                    final ComponentName requested, resolved;
                    LaunchActivityIdentity(ComponentName requested, ComponentName resolved) {
                        this.requested = requested; this.resolved = resolved;
                    }
                    boolean matches(ComponentName component) { return requested.equals(component) || resolved.equals(component); }
                    boolean matchesPackage(String pkg) { return requested.pkg.equals(pkg); }
                """ + RuntimeSourceFixture.methods("LaunchActivityIdentity", "matchesStart") + "}\n"
                + RuntimeSourceFixture.nestedClass("ShellActivityLaunchScope", "ShellActivityLaunchScope")
                        .replace("final class ShellActivityLaunchScope", "static final class ShellActivityLaunchScope")
                + """
                record TaskState(ComponentName component) {}
                static class Membership { boolean hasPhoneDesktop() { return false; } }
                static class Guard {
                    static final int NON_PHONE_LAUNCH_FLAGS = 0;
                    final boolean mEnabled = true;
                    final Membership mMembership = new Membership();
                    final Map<Integer, TaskState> mDesktopTasks = new HashMap<>();
                """ + RuntimeSourceFixture.methods("ShellExternalTaskMigrationGuard", "findPhoneLaunchTarget")
                + "}\n" + """
                public static void verify() throws Exception {
                    ComponentName main = new ComponentName("app", "Main");
                    ComponentName alias = new ComponentName("app", "Alias");
                    LaunchActivityIdentity identity = new LaunchActivityIdentity(alias, main);
                    Intent intent = new Intent(main);
                    TaskState task = new TaskState(main);
                    Guard guard = new Guard();
                    guard.mDesktopTasks.put(7, task);
                """ + scenario + "}\n", "PackageNameValidator");
    }
}
