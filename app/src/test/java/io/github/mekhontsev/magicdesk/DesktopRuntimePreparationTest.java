package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.assertTrue;

public final class DesktopRuntimePreparationTest {
    @Test public void coldStartNeedsReadyCallbackAndEachStartGetsItsOwnAcknowledgement() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    prepareDesktop(new Context(), 7);
                    check(starts == 1 && DESKTOP_PREPARATIONS.isEmpty(), "cold start not acknowledged");
                    acknowledge = false;
                    try { prepareDesktop(new Context(), 7); throw new AssertionError("previous acknowledgement reused"); }
                    catch (IOException expected) { check(expected.getCause() instanceof TimeoutException, "wrong failure"); }
                    check(DESKTOP_PREPARATIONS.isEmpty(), "failed preparation retained callback");
                    acknowledge = true;
                    prepareDesktop(new Context(), 7);
                    check(starts == 3, "retry did not start service");
                }
                """);
    }

    @Test public void preparationPrecedesHomeLease() throws Exception {
        final String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "DesktopSessionController.java"));
        assertTrue(source.indexOf("MagicDeskRuntime.prepareDesktop(")
                < source.indexOf("DesktopHomeRoleLease.prepare("));
        assertTrue(source.indexOf("RuntimeCapabilities.requireDesktop();")
                < source.indexOf("DisplayProfileController.prepareTarget("));
        assertTrue(source.indexOf("DesktopRuntimeBridge.noteDesktopTarget(")
                < source.indexOf("DesktopSystemTheme.prepare();"));
        assertTrue(source.indexOf("DesktopSystemTheme.prepare();")
                < source.indexOf("DesktopHomeRoleLease.activate("));
    }

    @Test public void selfTestSelectsDisplayBeforeRecordedDesktopStartup() throws Exception {
        RuntimeSourceFixture.verify("""
                enum DesktopSelfTestTarget { PHONE, SIMULATED, EXTERNAL }
                static class DesktopDisplayTarget {
                    static final DesktopDisplayTarget PHONE = new DesktopDisplayTarget();
                    static DesktopDisplayTarget phone() { return PHONE; }
                }
                static class DesktopRuntimeBridge { static boolean hasWorkspaces() { return false; } }
                static class DesktopSelfTestController { static String unavailableReason(Object c) { return null; } }
                static class DesktopSelfTestHostObserver { static void begin(long id) {} }
                Object mContext;
                long mRunId = 1;
                DesktopSelfTestTarget mTarget = DesktopSelfTestTarget.PHONE;
                boolean preparing() { return true; }
                void finishPreparation(boolean cancel,String reason) { throw new AssertionError(reason); }
                void completePreparation(boolean cancel,String reason) { throw new AssertionError(reason); }
                int runs, externalProbes;
                DesktopDisplayTarget selectedDisplay;
                void run(DesktopDisplayTarget target) {
                    runs++;
                    selectedDisplay = target;
                }
                void probeExternal() { externalProbes++; }
                public static void verify() {
                    Fixture phone = new Fixture();
                    phone.prepare();
                    check(phone.runs == 1 && phone.selectedDisplay == DesktopDisplayTarget.PHONE,
                            "phone target did not reach the recorded test");
                    check(phone.externalProbes == 0, "phone requested external display discovery");

                    Fixture simulated = new Fixture();
                    simulated.mTarget = DesktopSelfTestTarget.SIMULATED;
                    simulated.prepare();
                    check(simulated.runs == 1 && simulated.selectedDisplay == null,
                            "simulated display must be created inside the recorded test");
                    check(simulated.externalProbes == 0, "simulated target requested external display discovery");

                    Fixture external = new Fixture();
                    external.mTarget = DesktopSelfTestTarget.EXTERNAL;
                    external.prepare();
                    check(external.externalProbes == 1 && external.runs == 0,
                            "external test started without selecting its display");
                }
                """ + RuntimeSourceFixture.methods("DesktopSelfTestLauncher", "prepare"));
    }

    private static String fixture() throws Exception {
        return """
                static class Context {
                    void startForegroundService(Intent intent) { starts++; if (acknowledge) desktopRuntimePrepared(intent.displayId); }
                }
                static class Intent {
                    int displayId;
                    Intent(Context context, Class<?> service) {}
                    Intent putExtra(String key, int id) { displayId = id; return this; }
                }
                static final String EXTRA_PREPARING_DISPLAY = "display";
                static class MagicDeskRuntimeService {}
                static class Looper { static Object myLooper() { return null; }
                    static Object getMainLooper() { return new Object(); } }
                static class ExternalDisplayController { static final long START_TIMEOUT_MS = 5L; }
                static class MagicDeskRuntime {}
                static class RuntimeCapabilities { static void requireDesktop() {} }
                static Map<Integer, CompletableFuture<Void>> DESKTOP_PREPARATIONS = new java.util.concurrent.ConcurrentHashMap<>();
                static boolean acknowledge = true;
                static int starts;

                """ + RuntimeSourceFixture.methods("MagicDeskRuntime", "prepareDesktop", "desktopRuntimePrepared")
                        .replace("android.os.Looper", "Looper");
    }
}
