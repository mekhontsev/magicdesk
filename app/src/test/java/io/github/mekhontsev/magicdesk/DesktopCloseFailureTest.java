package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopCloseFailureTest {
    @Test
    public void closeContinuesAfterEachCleanupFailureWithoutReopening() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String TAG = "test";
                static final List<String> events = new ArrayList<>();
                static String failure;
                static int active, completions;
                static void step(String name) {
                    events.add(name);
                    if (name.equals(failure)) throw new IllegalStateException(name);
                }
                static class Display { static final int DEFAULT_DISPLAY = 0; }
                interface CompletionCallback { void onComplete(boolean success); }
                static class DesktopDisplayTarget {
                    enum Kind { WIRED, SIMULATED }
                    int displayId = 7;
                    Kind kind = Kind.WIRED;
                }
                static class DesktopCloseMode { boolean parkTasks = true, showControlPanel = true; }
                static class Log { static void i(String a, String b) {} static void w(String a, String b) {}
                    static void w(String a, String b, Throwable e) {} }
                static class CompatibilityDiagnostics { static void record(Object... args) {} }
                static class DesktopTransitionGate {
                    enum Operation { CLOSE }
                    void finish(Operation operation) { events.add("finished"); }
                }
                final DesktopTransitionGate mGate = new DesktopTransitionGate();
                static class Queue { void execute(Runnable r) { r.run(); } }
                final Queue mOperations = new Queue();
                static class DesktopHomeRoleLease {
                    static class RestoredHomePresentation {}
                    static RestoredHomePresentation releaseForSessionClose(DesktopDisplayTarget target)
                            throws IOException { step("home"); return new RestoredHomePresentation(); }
                    static void presentRestoredHome(RestoredHomePresentation p) throws IOException {
                        step("present");
                    }
                    static void releaseAfterSessionLoss(int display) throws IOException { step("release-lost"); }
                }
                static class MagicDeskRuntime {
                    static void releaseDesktopInput(int id, Runnable completion) {
                        step("input"); completion.run();
                    }
                    static void disableExternalTaskMigrationProtection() { step("protection"); }
                    static void parkDesktopTasks(DesktopDisplayTarget t, CompletionCallback c) {
                        step("park"); c.onComplete(true);
                    }
                }
                static class DesktopRuntimeBridge {
                    static int getActiveDesktopDisplayId() { return active; }
                    static boolean isLocalDesktopActiveOrStarting() { return false; }
                }
                static class PhoneDesktopTaskRecovery {
                    static class Result { boolean success = true, cancelled; String message = ""; }
                    static Result recoverBlocking(java.util.function.BooleanSupplier inactive) {
                        step("recover"); return new Result();
                    }
                }
                static class ControlActivity { static boolean isControlPanelVisible() { return false; } }
                static class PhoneControlPanelLauncher {
                    static void openOnPhoneWithShell() { step("panel"); }
                }
                static boolean removeSimulatedDesktop(int display) { step("remove"); return false; }
                static boolean closeDesktopSessionAndWait(int display) {
                    active = -1; step("close"); return true;
                }
                public static void verify() {
                    for (String fail : List.of("none", "home", "protection", "park", "close",
                            "recover", "present", "panel", "remove")) {
                        failure = fail; active = 7; completions = 0; events.clear();
                        Fixture f = new Fixture();
                        DesktopDisplayTarget target = new DesktopDisplayTarget();
                        if (fail.equals("remove")) target.kind = DesktopDisplayTarget.Kind.SIMULATED;
                        f.beginDesktopClose(target, new DesktopCloseMode(), ok -> completions++);
                        check(completions == 1, "close did not complete once after " + fail);
                        check(active == -1, "session retained after " + fail);
                        check(events.indexOf("home") < events.indexOf("input"), "HOME was not first");
                        check(events.indexOf("input") < events.indexOf("park"), "input survived into parking");
                        check(events.contains("recover") && events.contains("panel")
                                && events.contains("finished"), "cleanup stopped after " + fail + ": " + events);
                    }
                }
                """ + RuntimeSourceFixture.methods("DesktopSessionTransitionCoordinator",
                        "beginDesktopClose", "parkAndClose", "finishDesktopSessionClose",
                        "finishDesktopClose", "recordCloseFailure", "shouldOpenPhonePanel", "complete"));
    }
}
