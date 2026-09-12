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
    }

    @Test public void phoneSelfTestUsesProductionStartQueueBeforeObservingHost() throws Exception {
        RuntimeSourceFixture.verify("""
                enum DesktopSelfTestTarget { PHONE, SIMULATED }
                static class DesktopDisplayTarget { static Object phone() { return "phone"; } }
                static class DesktopSessionPolicy { static Object ISOLATED_SELF_TEST = new Object(); }
                static class Display { static int INVALID_DISPLAY = -1; }
                static class DesktopRuntimeBridge { static boolean hasWorkspaces() { return false; } }
                static class DesktopSelfTestController { static String unavailableReason(Object c) { return null; } }
                static class DesktopSelfTestHostObserver { static void begin(long id) {} }
                static class DesktopOperations {
                    static boolean queued;
                    static void showDesktop(Object target, Object policy) { queued = true; }
                }
                Object mContext;
                long mRunId = 1;
                DesktopSelfTestTarget mTarget = DesktopSelfTestTarget.PHONE;
                boolean preparing() { return true; }
                void finishPreparation(boolean cancel,String reason) { throw new AssertionError(reason); }
                void completePreparation(boolean cancel,String reason) { throw new AssertionError(reason); }
                void run() { throw new AssertionError("unexpected simulated test"); }
                void probeExternal() { throw new AssertionError("unexpected external test"); }
                boolean observing;
                void waitForDesktop(Object kind) {
                    check(DesktopOperations.queued, "host observation preceded queued start");
                    observing = true;
                }
                public static void verify() {
                    Fixture fixture = new Fixture();
                    fixture.prepare();
                    check(fixture.observing, "phone host readiness not observed");
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
