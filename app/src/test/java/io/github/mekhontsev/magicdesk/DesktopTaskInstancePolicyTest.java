package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;
import org.junit.Test;

public final class DesktopTaskInstancePolicyTest {
    @Test
    public void singletonLaunchModesCannotCreateAnotherTask() {
        for (int mode : new int[] {ActivityInfo.LAUNCH_SINGLE_TASK, ActivityInfo.LAUNCH_SINGLE_INSTANCE}) {
            for (int document : new int[] {ActivityInfo.DOCUMENT_LAUNCH_NONE,
                    ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING, ActivityInfo.DOCUMENT_LAUNCH_ALWAYS}) {
                assertFalse(DesktopTaskInstancePolicy.supportsNewWindow(mode, document));
            }
        }
    }

    @Test
    public void ordinaryAndPerTaskActivitiesSupportExplicitMultipleTasks() {
        for (int mode : new int[] {ActivityInfo.LAUNCH_MULTIPLE, ActivityInfo.LAUNCH_SINGLE_TOP,
                ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK}) {
            for (int document : new int[] {ActivityInfo.DOCUMENT_LAUNCH_NONE,
                    ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING, ActivityInfo.DOCUMENT_LAUNCH_ALWAYS}) {
                assertTrue(DesktopTaskInstancePolicy.supportsNewWindow(mode, document));
            }
            assertFalse(DesktopTaskInstancePolicy.supportsNewWindow(mode, ActivityInfo.DOCUMENT_LAUNCH_NEVER));
        }
    }

    @Test
    public void rejectionPrecedesIntentChangesAndReuseRemainsAvailable() throws Exception {
        verify("""
                pm.info.launchMode = ActivityInfo.LAUNCH_SINGLE_TASK;
                ApplicationTaskPlacement.exists = true;
                Intent source = new Intent();
                source.component = new ComponentName();
                source.flags = 16;
                try {
                    DesktopTaskInstancePolicy.CREATE_NEW.applyTo(pm, source);
                    throw new AssertionError("singleton accepted new window");
                } catch (IllegalArgumentException expected) {
                    check(expected.getMessage().contains("already open"), "unclear rejection");
                }
                check(source.flags == 16 && pm.reads == 1 && ApplicationTaskPlacement.checks == 1,
                        "modified caller intent or missed task check");
                Intent reuse = DesktopTaskInstancePolicy.REUSE_EXISTING.applyTo(pm, source);
                check(reuse != source && reuse.flags == 16 && pm.reads == 1, "reuse acquired a new-window gate");
                """);
    }

    @Test
    public void explicitAndResolvedTargetsUseTheirManifestBeforeApplyingFlags() throws Exception {
        verify("""
                for (boolean explicit : new boolean[] {false, true}) {
                    Intent source = new Intent();
                    source.component = explicit ? new ComponentName() : null;
                    pm.info.launchMode = ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK;
                    Intent result = DesktopTaskInstancePolicy.CREATE_NEW.applyTo(pm, source);
                    check(result.flags == 3 && source.flags == 0, "new task flags or original intent changed");
                    pm.info.documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_NEVER;
                    ApplicationTaskPlacement.exists = true;
                    try {
                        DesktopTaskInstancePolicy.CREATE_NEW.applyTo(pm, source);
                        throw new AssertionError("document-never accepted document task flags");
                    } catch (IllegalArgumentException expected) { }
                    ApplicationTaskPlacement.exists = false;
                    pm.info.documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_NONE;
                }
                check(pm.reads == 4, "did not inspect both explicit and resolved activities");
                """);
    }

    @Test
    public void firstInstanceIsAllowedAndOnlyRestrictedModesQueryTasks() throws Exception {
        verify("""
                Intent source = new Intent();
                source.component = new ComponentName();
                for (int mode : new int[] {ActivityInfo.LAUNCH_SINGLE_TASK, ActivityInfo.LAUNCH_SINGLE_INSTANCE}) {
                    pm.info.launchMode = mode;
                    Intent first = DesktopTaskInstancePolicy.CREATE_NEW.applyTo(pm, source);
                    check(first.flags == 3 && source.flags == 0, "blocked or changed first launch");
                }
                check(ApplicationTaskPlacement.checks == 2, "missing restricted-mode task checks");
                pm.info.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
                DesktopTaskInstancePolicy.CREATE_NEW.applyTo(pm, source);
                DesktopTaskInstancePolicy.REUSE_EXISTING.applyTo(pm, source);
                check(ApplicationTaskPlacement.checks == 2, "ordinary launch acquired task query");
                """);
    }

    @Test
    public void missingActivityIsNotReportedAsSupportingMultipleWindows() throws Exception {
        verify("""
                for (boolean explicit : new boolean[] {false, true}) {
                    Intent source = new Intent();
                    source.component = explicit ? new ComponentName() : null;
                    pm.info = null;
                    try {
                        DesktopTaskInstancePolicy.CREATE_NEW.applyTo(pm, source);
                        throw new AssertionError("unavailable activity accepted");
                    } catch (IllegalArgumentException expected) {
                        check(expected.getMessage().equals("Activity is unavailable"), "lost availability error");
                    }
                }
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class ComponentName {
                    ComponentName() {}
                    ComponentName(String pkg, String name) {}
                }
                static class android {
                    static class os {
                        static class Process { static Object myUserHandle() { return null; } }
                    }
                }
                static class FrameworkUserApi { static int userId(Object handle) { return 10; } }
                static class LaunchActivityIdentity {
                    static LaunchActivityIdentity resolve(int user, PackageManager pm, ComponentName component) {
                        check(user == 10 && component != null, "lost profile or component");
                        return new LaunchActivityIdentity();
                    }
                }
                static class ApplicationTaskPlacement {
                    static boolean exists; static int checks;
                    static void rejectExistingInstance(LaunchActivityIdentity identity) {
                        checks++;
                        if (exists) throw new IllegalArgumentException("already open");
                    }
                }
                static class Intent {
                    static final int FLAG_ACTIVITY_NEW_DOCUMENT = 1, FLAG_ACTIVITY_MULTIPLE_TASK = 2;
                    int flags; ComponentName component;
                    Intent() {}
                    Intent(Intent other) { flags = other.flags; component = other.component; }
                    ComponentName getComponent() { return component; }
                    void removeFlags(int value) { flags &= ~value; }
                    void addFlags(int value) { flags |= value; }
                }
                static class ActivityInfo {
                    static final int LAUNCH_MULTIPLE = 0, LAUNCH_SINGLE_TOP = 1,
                            LAUNCH_SINGLE_TASK = 2, LAUNCH_SINGLE_INSTANCE = 3, LAUNCH_SINGLE_INSTANCE_PER_TASK = 4;
                    static final int DOCUMENT_LAUNCH_NONE = 0, DOCUMENT_LAUNCH_NEVER = 3;
                    int launchMode, documentLaunchMode;
                    String packageName = "app", name = "Main";
                }
                record ResolveInfo(ActivityInfo activityInfo) {}
                static class PackageManager {
                    static final int MATCH_DEFAULT_ONLY = 1;
                    static class NameNotFoundException extends Exception {}
                    static class ComponentInfoFlags { static int of(int value) { return value; } }
                    static class ResolveInfoFlags { static int of(int value) { return value; } }
                    ActivityInfo info = new ActivityInfo(); int reads;
                    ActivityInfo getActivityInfo(ComponentName component, int flags) throws NameNotFoundException {
                        reads++; if (info == null) throw new NameNotFoundException(); return info;
                    }
                    ResolveInfo resolveActivity(Intent intent, int flags) { reads++; return new ResolveInfo(info); }
                }
                public static void verify() {
                    PackageManager pm = new PackageManager();
                """ + scenario + "}\n" + RuntimeSourceFixture.nestedClass(
                "DesktopTaskInstancePolicy", "DesktopTaskInstancePolicy"));
    }
}
