package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ShellActivityLaunchScopeTest {
    @Test
    public void explicitLaunchWithStrippedFlagsIsNotAPhoneMigration() throws Exception {
        verify("""
                check(guard.findPhoneLaunchTarget(intent, "app") == task, "ordinary phone launch no longer migrates");
                try (var scope = ShellActivityLaunchScope.begin(identity, 7)) {
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
                try (var scope = ShellActivityLaunchScope.begin(identity, 7)) {
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
                try (var outer = ShellActivityLaunchScope.begin(identity, 7)) {
                    try (var inner = ShellActivityLaunchScope.begin(identity, 7)) {
                        throw new IllegalStateException("launch aborted");
                    } catch (IllegalStateException expected) { }
                    check(ShellActivityLaunchScope.isActive(intent, "app"), "inner cleanup released outer scope");
                }
                check(!ShellActivityLaunchScope.isActive(intent, "app"), "failed scope leaked");
                var scope = ShellActivityLaunchScope.begin(identity, 7);
                scope.close(); scope.close();
                check(!ShellActivityLaunchScope.isActive(intent, "app"), "close was not idempotent");
                """);
    }

    @Test
    public void destinationMustBeExplicitAndUnambiguous() throws Exception {
        verify("""
                RuntimeException failure = new IllegalStateException("separator missing");
                check(!ShellActivityLaunchScope.rejectOnDisplay(intent, "app", 7, failure), "inferred native destination");
                try (var desktop = ShellActivityLaunchScope.begin(identity, 7)) {
                    check(!ShellActivityLaunchScope.rejectOnDisplay(intent, "app", 0, failure), "confused phone and Desktop");
                    try (var phone = ShellActivityLaunchScope.begin(identity, 0)) {
                        check(ShellActivityLaunchScope.isActive(intent, "app"), "lost explicit provenance");
                        check(!ShellActivityLaunchScope.rejectOnDisplay(intent, "app", 7, failure), "guessed ambiguous destination");
                        check(!ShellActivityLaunchScope.rejectOnDisplay(intent, "app", 0, failure), "guessed ambiguous destination");
                        phone.requireAdmitted();
                    }
                    desktop.requireAdmitted();
                    check(ShellActivityLaunchScope.rejectOnDisplay(new Intent(alias), "app", 7, failure), "lost alias destination");
                    try { desktop.requireAdmitted(); throw new AssertionError("timeout instead of rejection"); }
                    catch (IllegalStateException rejected) { check(rejected == failure, "lost root cause"); }
                }
                check(!ShellActivityLaunchScope.rejectOnDisplay(intent, "app", 7, failure), "destination leaked");
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
