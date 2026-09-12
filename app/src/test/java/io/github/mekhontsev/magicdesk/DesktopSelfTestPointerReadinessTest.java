package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopSelfTestPointerReadinessTest {
    @Test
    public void observesPointerReadinessForTheSelectedDisplay() throws Exception {
        RuntimeSourceFixture.verify("""
                    static final long STEP_TIMEOUT_MILLIS = 100;
                    static class SystemClock {
                        static long now;
                        static long uptimeMillis() { return now; }
                    }
                    static class DesktopSelfTestRunState {
                        static void checkpoint() {}
                    }
                    static class DesktopRuntimeBridge {
                        static int display = 4;
                        static boolean hasWorkspace(int id) { return display == id; }
                    }
                    static class MagicDeskRuntime {
                        static boolean ready;
                        static boolean isPointerTransportReady() { return ready; }
                    }
                    static class DesktopAutomationEventJournal {
                        static int waits;
                        static boolean publishReady;
                        static boolean interrupt;
                        static long latestId() { return 10; }
                        static long awaitChange(long id, long remaining)
                                throws InterruptedException {
                            waits++;
                            if (interrupt) throw new InterruptedException();
                            SystemClock.now += remaining;
                            MagicDeskRuntime.ready = publishReady;
                            return id + 1;
                        }
                    }
                    public static void verify() throws Exception {
                        MagicDeskRuntime.ready = true;
                        if (!awaitVirtualPointer(4).contains("routing ready"))
                            throw new AssertionError();
                        if (DesktopAutomationEventJournal.waits != 0)
                            throw new AssertionError("ready pointer must not wait");
                        MagicDeskRuntime.ready = false;
                        DesktopAutomationEventJournal.publishReady = true;
                        awaitVirtualPointer(4);
                        if (DesktopAutomationEventJournal.waits != 1)
                            throw new AssertionError("must observe readiness event");
                        DesktopRuntimeBridge.display = 5;
                        expectFailure("did not become ready");
                        DesktopRuntimeBridge.display = 4;
                        MagicDeskRuntime.ready = false;
                        DesktopAutomationEventJournal.publishReady = false;
                        expectFailure("did not become ready");
                        DesktopAutomationEventJournal.interrupt = true;
                        expectFailure("wait interrupted");
                        if (!Thread.interrupted()) throw new AssertionError();
                    }
                    static void expectFailure(String message) throws Exception {
                        try {
                            awaitVirtualPointer(4);
                            throw new AssertionError("missing readiness was accepted");
                        } catch (IOException expected) {
                            if (!expected.getMessage().contains(message)) throw expected;
                        }
                    }
                """ + RuntimeSourceFixture.methods("DesktopSelfTestInputSuite",
                        "awaitVirtualPointer"));
    }
}
