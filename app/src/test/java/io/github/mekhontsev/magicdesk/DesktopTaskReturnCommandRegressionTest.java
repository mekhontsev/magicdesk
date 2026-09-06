package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopTaskReturnCommandRegressionTest {
    @Test public void partialReturnReportsFailureAndContinuesRecovery() throws Exception {
        verify("TaskFullscreenMoveCommand.failTask = 1; main(new String[]{\"4\"}); check(DesktopTaskReturnResult.failed == 1 && DesktopTaskReturnResult.returned == 1, \"partial failure missing from result\"); check(TaskFullscreenMoveCommand.attempts == 2, \"recovery stopped after one task\");");
    }
    @Test public void successfulReturnReportsBothTasks() throws Exception {
        verify("main(new String[]{\"4\"}); check(DesktopTaskReturnResult.failed == 0 && DesktopTaskReturnResult.returned == 2, \"successful result missing\");");
    }
    @Test public void totalFailureRemainsFailure() throws Exception {
        verify("TaskFullscreenMoveCommand.failTask = -2; main(new String[]{\"4\"}); check(DesktopTaskReturnResult.failed == 2 && DesktopTaskReturnResult.returned == 0, \"total failure missing\");");
    }
    @Test public void unrelatedMagicdeskSuffixIsNotOurApplication() throws Exception {
        verify("check(!isMagicDeskPackage(new ComponentName(\"org.example.magicdesk\")), \"unrelated package excluded\"); check(isMagicDeskPackage(new ComponentName(BuildConfig.APPLICATION_ID)), \"own package not excluded\");");
    }
    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final int PHONE_DISPLAY_ID = 0;
                static class ComponentName { final String name; ComponentName(String name) { this.name = name; } String getPackageName() { return name; } }
                static class BuildConfig { static final String APPLICATION_ID = "io.github.mekhontsev.magicdesk"; }
                static class FrameworkTaskSnapshot { static final int ACTIVITY_TYPE_STANDARD = 1; }
                static class DesktopTaskDensity { static final int INHERIT = 0; }
                static class HiddenTaskApi {
                    static Object getService() { return new Object(); }
                    static List<Integer> getTasks(Object service, int display) { return List.of(1, 2); }
                    static int getTaskId(Object task) { return (Integer) task; }
                    static int getTaskActivityType(Object task) { return 1; }
                    static ComponentName getTaskTopActivity(Object task) { return null; }
                    static ComponentName getTaskBaseActivity(Object task) { return null; }
                }
                static class TaskFullscreenMoveCommand {
                    static int failTask = -1, attempts;
                    static void moveTask(Object service, int task, int from, int to, int density) throws ReflectiveOperationException {
                        attempts++; if (task == failTask || failTask == -2) throw new IllegalStateException("task return failed");
                    }
                }
                static class DesktopTaskReturnResult {
                    static int returned = -1, failed = -1;
                    static String encode(int display, int moved, int errors) { check(display == 4, "wrong source display"); returned = moved; failed = errors; return "structured-result"; }
                }
                static class System {
                    static final PrintStream out = new PrintStream(new ByteArrayOutputStream());
                    static final PrintStream err = out;
                    static void exit(int code) { throw new AssertionError("unexpected process exit " + code); }
                }
                public static void verify() {
                """ + scenario + "}\n" + RuntimeSourceFixture.methods("DesktopTaskReturnCommand",
                "main", "findSelectedTasks", "findApplicationTasks", "isMagicDeskTask",
                "isMagicDeskPackage", "getActivityType", "parseDisplayId", "parseTaskIds", "usefulMessage"));
    }
}
