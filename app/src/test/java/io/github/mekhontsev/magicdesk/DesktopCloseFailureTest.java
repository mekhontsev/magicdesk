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
                static boolean selectedRecovery, expectedRecovery;
                static void step(String name) {
                    events.add(name);
                    if (name.equals(failure)) throw new IllegalStateException(name);
                }
                static class Display { static final int DEFAULT_DISPLAY = 0, INVALID_DISPLAY = -1; }
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
                static class PhoneUi {
                    boolean isPhoneScreenControlActive() { return !failure.equals("screen-unowned"); }
                    boolean setPhoneScreenOff(boolean off, int displayId) {
                        check(!off && displayId == Display.INVALID_DISPLAY, "not a phone restore");
                        step("phone");
                        return !failure.equals("phone-result");
                    }
                }
                final PhoneUi mPhoneUi = new PhoneUi();
                static class DesktopHomeRoleLease {
                    static class RestoredHomePresentation {}
                    static void releaseForSessionClose(DesktopDisplayTarget target)
                            throws IOException {
                        selectedRecovery = !expectedRecovery;
                        step("home");
                    }
                    static RestoredHomePresentation finishSessionClose(DesktopDisplayTarget target)
                            throws IOException { step("surfaces"); return new RestoredHomePresentation(); }
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
                    static Result recoverBlocking(boolean required, java.util.function.BooleanSupplier inactive) {
                        check(required == expectedRecovery, "close reread next-session recovery setting");
                        step("recover"); return new Result();
                    }
                }
                static class DesktopCompatibilityPolicy {
                    enum Option { PHONE_TASK_RECOVERY }
                    boolean enabled(Option option) { return selectedRecovery; }
                }
                static class DesktopCompatibilitySettings {
                    static DesktopCompatibilityPolicy current() { return new DesktopCompatibilityPolicy(); }
                }
                static class ControlActivity { static boolean isControlPanelVisible() { return false; } }
                static class SecondaryDisplayWindowing {
                    static void release(int displayId) throws IOException {
                        step("display-mode");
                        if (failure.equals("display-mode-io")) throw new IOException("restore failed");
                    }
                }
                static class PhoneControlPanelLauncher {
                    static void openOnPhoneWithShell() { step("panel"); }
                }
                static boolean removeSimulatedDesktop(int display) { step("remove"); return false; }
                static boolean closeDesktopSessionAndWait(int display) {
                    active = -1; step("close"); return true;
                }
                public static void verify() {
                    for (String fail : List.of("none", "home", "phone", "phone-result",
                            "screen-unowned", "protection", "park", "close",
                            "recover", "display-mode", "display-mode-io",
                            "surfaces", "present", "panel", "remove")) {
                        failure = fail; active = 7; completions = 0; events.clear();
                        selectedRecovery = expectedRecovery = true;
                        Fixture f = new Fixture();
                        DesktopDisplayTarget target = new DesktopDisplayTarget();
                        if (fail.equals("remove")) target.kind = DesktopDisplayTarget.Kind.SIMULATED;
                        boolean[] succeeded = {false};
                        f.beginDesktopClose(target, new DesktopCloseMode(), ok -> {
                            completions++; succeeded[0] = ok;
                        });
                        check(completions == 1, "close did not complete once after " + fail);
                        check(active == -1, "session retained after " + fail);
                        check(events.indexOf("home") < events.indexOf("input"), "HOME was not first");
                        if (fail.equals("screen-unowned")) {
                            check(!events.contains("phone"), "changed unowned phone power");
                        } else {
                            check(events.indexOf("home") < events.indexOf("phone")
                                    && events.indexOf("phone") < events.indexOf("input"),
                                    "phone restore outside HOME/input boundary: " + events);
                        }
                        if (fail.equals("phone") || fail.equals("phone-result")) {
                            check(!succeeded[0], "lost phone restore failure");
                        }
                        check(events.indexOf("input") < events.indexOf("park"), "input survived into parking");
                        check(events.indexOf("close") < events.indexOf("surfaces"),
                                "HOME surfaces disabled before host close: " + events);
                        check(events.indexOf("close") < events.indexOf("display-mode")
                                && events.indexOf("display-mode") < events.indexOf("surfaces"),
                                "display default restored outside teardown boundary: " + events);
                        if (fail.startsWith("display-mode")) {
                            check(!succeeded[0], "lost display default restoration failure");
                        }
                        if (fail.equals("remove")) check(events.indexOf("remove") < events.indexOf("surfaces"),
                                "HOME surfaces disabled before display removal: " + events);
                        check(events.contains("recover") && events.contains("panel")
                                && events.contains("finished"), "cleanup stopped after " + fail + ": " + events);
                    }
                    failure = "none"; active = 7; events.clear();
                    selectedRecovery = expectedRecovery = false;
                    new Fixture().beginDesktopClose(new DesktopDisplayTarget(), new DesktopCloseMode(), ok -> {
                        check(ok, "disabled recovery prevented normal close");
                    });
                    check(events.contains("park") && events.contains("close") && events.contains("finished"),
                            "disabling optional recovery bypassed owned cleanup");
                }
                """ + RuntimeSourceFixture.methods("DesktopSessionTransitionCoordinator",
                        "beginDesktopClose", "parkAndClose", "finishDesktopSessionClose",
                        "finishDesktopClose", "recordCloseFailure", "shouldOpenPhonePanel", "complete"));
    }
}
