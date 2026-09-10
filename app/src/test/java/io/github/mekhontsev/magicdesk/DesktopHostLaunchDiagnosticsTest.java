package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopHostLaunchDiagnosticsTest {
    @Test public void timeoutRetainsStartResultAndWrongDisplayHost() throws Exception {
        verify("""
                launchFailure = new IllegalStateException("task did not appear");
                observed.add(task(42, 0, 2, "magicdesk/.DesktopActivity"));
                observed.add(task(43, 4, 2, "launcher/.Home"));
                observed.add(task(44, 4, 1, "private.app/.Editor"));
                IllegalStateException failure = failedLaunch();
                String message = failure.getMessage();
                check(message.contains("stage=await-task") && message.contains("startResult=0"), message);
                check(message.contains("uid=0 binderCallerUid=10123"), message);
                check(message.contains("targetDisplay=4") && message.contains("id=42 user=0 display=0"), message);
                check(message.contains("launcher/.Home") && !message.contains("private.app"), message);
                check(failure.getCause() == launchFailure, "original failure lost");
                check(reads == 1 && removals == 0 && registrations == 0, "diagnostics changed launch policy");
                """);
    }

    @Test public void rejectedHomeIsCapturedBeforeRemoval() throws Exception {
        verify("""
                actualType = 1;
                observed.add(task(42, 4, 1, "magicdesk/.DesktopActivity"));
                IllegalStateException failure = failedLaunch();
                String message = failure.getMessage();
                check(message.contains("stage=verify-home-type") && message.contains("matchedTask=42 matchedType=1"), message);
                check(message.contains("id=42 user=0 display=4 root=42 area=1 type=1 mode=1"), message);
                check(reads == 1 && removals == 1 && registrations == 0, "rejected task cleanup changed");
                """);
    }

    @Test public void successfulHomeAddsNoDiagnosticRead() throws Exception {
        verify("""
                check(new Fixture().launch(4, "ignored") == 42, "launch identity changed");
                check(reads == 0 && removals == 0 && registrations == 1, "successful launch gained diagnostics");
                """);
    }

    @Test public void rejectedStartRetainsExactAndroidResult() throws Exception {
        verify("""
                startResult = -96;
                String message = failedLaunch().getMessage();
                check(message.contains("stage=start-rejected") && message.contains("startResult=-96"), message);
                check(reads == 1 && removals == 0, "start rejection triggered task mutation");
                """);
    }

    @Test public void noBinderResultIsNotReportedAsSuccess() throws Exception {
        verify("""
                beforeResultFailure = new SecurityException("launch denied");
                String message = failedLaunch().getMessage();
                check(message.contains("stage=start-activity") && message.contains("startResult=not-returned"), message);
                check(message.contains("launch denied"), "original rejection missing");
                """);
    }

    @Test public void unavailableSnapshotCannotPreventCleanupOrReplaceFailure() throws Exception {
        verify("""
                actualType = 1;
                snapshotFailure = new ReflectiveOperationException("unavailable");
                cleanupFailure = new IllegalStateException("cleanup denied");
                IllegalStateException failure = failedLaunch();
                check(failure.getMessage().contains("tasks=unavailable:ReflectiveOperationException"), failure.getMessage());
                check(failure.getCause().getMessage().equals("desktop host did not become a HOME task"), "original failure replaced");
                check(failure.getSuppressed().length == 1 && failure.getSuppressed()[0] == cleanupFailure,
                        "cleanup failure lost");
                check(reads == 1 && removals == 1, "failure introduced retry or skipped cleanup");
                """);
    }

    @Test public void evidenceIsBoundedAndDoesNotDumpIntentPayloads() throws Exception {
        verify("""
                beforeResultFailure = new IllegalStateException("X".repeat(2000));
                for (int i = 0; i < 20; i++) observed.add(task(42 + i, i, 2, "magicdesk/." + "X".repeat(500)));
                String message = failedLaunch().getMessage();
                check(message.length() <= 1600, "Binder/report evidence exceeded its budget");
                check(message.endsWith(" truncated=true"), "truncation was not explicit");
                check(message.contains("sampleLimit=32 sampled=20 relevant=20 shown=4"), message);
                check(!message.contains("SECRET_INTENT_DATA"), "Intent payload leaked");
                """);
    }

    @Test public void observerKeepsEvidenceInBinderExceptionMessage() throws Exception {
        verify("""
                launchFailure = new IllegalStateException("task did not appear");
                try {
                    new Fixture().launchDesktopHost(4, "ignored");
                    throw new AssertionError("observer accepted failed launch");
                } catch (IllegalStateException expected) {
                    String message = expected.getMessage();
                    check(message.startsWith("cannot launch desktop host: HOME launch:"), message);
                    check(message.contains("stage=await-task") && message.contains("startResult=0"), message);
                    check(message.contains("tasks=[]"), message);
                    check(message.length() < 1800, "evidence will not fit compatibility event detail");
                }
                """);
    }

    private static void verify(final String scenario) throws Exception {
        final String diagnosticMethods = RuntimeSourceFixture.methods("DesktopHostLaunchDiagnostics",
                "recordStartResult", "failure", "appendTasks", "singleLine");
        final String launcherMethods = RuntimeSourceFixture.methods("ShellDesktopHostLauncher",
                "launch", "findDesktopHostTaskIds", "removeStaleHostTasks", "isDesktopHostComponent");
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static final String HOST_PACKAGE = "magicdesk", HOST_CLASS = "magicdesk.DesktopActivity";
                final Object mService = new Object();
                final Ownership mOwnership = new Ownership();
                final boolean mClosed = false;
                final LauncherBridge mDesktopHostLauncher = new LauncherBridge();
                void reportDesktopTaskOwnership() { }
                static class LauncherBridge {
                    int launch(int display, String uri) throws ReflectiveOperationException {
                        return new Fixture().launch(display, uri);
                    }
                }
                static class ShellAccess {
                """ + RuntimeSourceFixture.methods("ShellAccess", "usefulMessage") + """
                }
                static int reads, removals, registrations, startResult, actualType = 2;
                static RuntimeException launchFailure, beforeResultFailure, cleanupFailure;
                static ReflectiveOperationException snapshotFailure;
                static final List<FrameworkTaskSnapshot> observed = new ArrayList<>();
                static class BuildConfig { static final String APPLICATION_ID = "magicdesk"; }
                static class Display { static final int INVALID_DISPLAY = -1; }
                static class SystemClock { static long elapsedRealtime() { return 5000L; } }
                record ComponentName(String packageName, String className) {
                    String getPackageName() { return packageName; }
                    String getClassName() { return className; }
                    String flattenToShortString() { return "magicdesk/.DesktopActivity"; }
                }
                static class Intent {
                    ComponentName getComponent() { return new ComponentName(HOST_PACKAGE, HOST_CLASS); }
                    public String toString() { return "SECRET_INTENT_DATA"; }
                }
                static class Ownership { void markDesktopHost(int id) { registrations++; } }
                static class HiddenTaskApi {
                    static List<Object> getTasks(Object s, int d) { return List.of(); }
                    static Object requireTask(Object s, int d, int t) { return t; }
                    static int getTaskActivityType(Object t) { return actualType; }
                    static int getTaskId(Object t) { return (Integer)t; }
                    static ComponentName getTaskTopActivity(Object t) { return null; }
                    static ComponentName getTaskBaseActivity(Object t) { return null; }
                }
                static class TaskControlCommand {
                    static boolean removeTask(Object s, int t) throws ReflectiveOperationException {
                        check(reads == 1, "removed rejected task before failure snapshot");
                        removals++;
                        if (cleanupFailure != null) throw cleanupFailure;
                        return true;
                    }
                }
                static class TaskDisplayAreaLaunchCommand {
                    static Intent createAppIntent(String uri) { return new Intent(); }
                    static int launchFullscreenTask(Object s, int d, Intent i, String p,
                            Object area, int type, java.util.function.IntConsumer result)
                            throws ReflectiveOperationException {
                        check(d == 4 && area == null && type == 2, "HOME launch options changed");
                        if (beforeResultFailure != null) throw beforeResultFailure;
                        result.accept(startResult);
                        if (startResult < 0) throw new IllegalStateException("startActivity returned " + startResult);
                        if (launchFailure != null) throw launchFailure;
                        return 42;
                    }
                """ + RuntimeSourceFixture.methods("TaskDisplayAreaLaunchCommand", "causeChain") + """
                    static final int FAILURE_MESSAGE_LIMIT = 500;
                }
                record FrameworkTaskSnapshot(int taskId, int userId, int displayId, int rootTaskId,
                        int displayAreaFeatureId, int activityType, int windowingMode, String packageName,
                        String topPackage, String componentName, String topActivityName, boolean visible, boolean focused) {
                    static final int ACTIVITY_TYPE_HOME = 2;
                    boolean isHome() { return activityType == 2; }
                }
                static FrameworkTaskSnapshot task(int id, int display, int type, String component) {
                    String pkg = component.split("/")[0];
                    return new FrameworkTaskSnapshot(id, 0, display, id, 1, type, 1, pkg, pkg,
                            component, component, true, false);
                }
                static class FrameworkTaskSnapshotSource {
                    static List<FrameworkTaskSnapshot> readWindowState(Object s, int d, int limit)
                            throws ReflectiveOperationException {
                        check(d == -1 && limit == 32, "failure query lost its scope or bound");
                        check(removals == 0, "snapshot happened after cleanup");
                        reads++;
                        if (snapshotFailure != null) throw snapshotFailure;
                        return observed;
                    }
                }
                static class DesktopHostLaunchDiagnostics {
                    static final int TASK_LIMIT = 32, REPORTED_TASK_LIMIT = 4, DETAIL_LIMIT = 1600;
                    final long mStartedAt = 0;
                    final int mUid = 0, mCallerUid = 10123, mDisplayId;
                    final String mComponent;
                    String stage = "remove-stale-hosts";
                    Integer startResult, matchedActivityType;
                    int matchedTaskId = -1;
                    DesktopHostLaunchDiagnostics(int display, String component) {
                        mDisplayId = display; mComponent = component;
                    }
                """ + diagnosticMethods + "}\n" + launcherMethods
                + RuntimeSourceFixture.methods("ShellTaskObserver", "launchDesktopHost") + """
                static IllegalStateException failedLaunch() throws Exception {
                    try { new Fixture().launch(4, "ignored"); throw new AssertionError("failure accepted"); }
                    catch (IllegalStateException expected) { return expected; }
                }
                public static void verify() throws Exception {
                """ + scenario + "}\n", "BoundedText");
    }
}
