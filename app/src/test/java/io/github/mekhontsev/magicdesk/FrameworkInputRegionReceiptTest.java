package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FrameworkInputRegionReceiptTest {
    @Test public void exactWindowDisplayAndRegionAreRequired() throws Exception {
        verify("""
                var token = InputWindowHandle.class.getMethod("getWindowToken");
                var display = InputWindowHandle.class.getField("displayId");
                var region = InputWindowHandle.class.getField("touchableRegion");
                var config = InputWindowHandle.class.getField("inputConfig");
                for (int mismatch = 0; mismatch < 6; mismatch++) {
                    var handle = new InputWindowHandle();
                    switch (mismatch) {
                        case 0 -> handle.window = new IBinder();
                        case 1 -> handle.displayId++;
                        case 2 -> handle.touchableRegion = new Region("bounding-box");
                        case 3 -> handle.inputConfig = 1;
                        case 4 -> handle.inputConfig = 2;
                        case 5 -> handle.inputConfig = 65_536;
                    }
                    check(!hasTouchableRegion(new InputWindowHandle[]{handle}, token, display, region,
                            config, target, 7, expected), "unrelated/hidden geometry accepted: " + mismatch);
                }
                var inert = new InputWindowHandle();
                inert.inputConfig = 4;
                check(hasTouchableRegion(new InputWindowHandle[]{null, inert}, token, display, region,
                        config, target, 7, expected), "keyboard-inert panel rejected");
                """);
    }

    @Test public void initialSnapshotAndChangedRegionBothCompleteAndUnregister() throws Exception {
        verify("""
                var first = observeTouchableRegion(target, 7, expected, completion);
                check(replies == 1 && error == null, "initial observation missing");
                first.close();
                check(unregistered == 1, "initial receipt leaked subscription");
                initialMatch = false;
                publish = true;
                var second = observeTouchableRegion(target, 7, expected, completion);
                check(replies == 2 && error == null, "callback observation missing");
                second.close();
                check(unregistered == 2, "callback receipt leaked subscription");
                """);
    }

    @Test public void failureCancellationAndInvalidArgumentsDoNotBecomeSuccess() throws Exception {
        verify("""
                failRead = true;
                var failed = observeTouchableRegion(target, 7, expected, completion);
                check(replies == 1 && error != null, "read error accepted");
                failed.close();
                failRead = false;
                initialMatch = false;
                var cancelled = observeTouchableRegion(target, 7, expected, completion);
                cancelled.close();
                last.onWindowInfosChanged(new InputWindowHandle[]{new InputWindowHandle()}, null);
                check(replies == 1, "late event revived cancelled observation");
                check(unregistered == 2, "failure leaked subscription");
                try { observeTouchableRegion(null, 7, expected, completion); throw new AssertionError("no identity accepted"); }
                catch (IllegalArgumentException expectedFailure) { }
                check(registered == 2, "invalid request registered");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class IBinder { }
                static class AtomicBoolean extends java.util.concurrent.atomic.AtomicBoolean { }
                interface Consumer<T> extends java.util.function.Consumer<T> { }
                record Region(String shape) { }
                static IBinder target = new IBinder();
                static Region expected = new Region("two-strips");
                static boolean initialMatch = true, publish, failRead;
                static int registered, unregistered, replies;
                static Throwable error;
                static Consumer<Throwable> completion = failure -> { replies++; error = failure; };
                static WindowInfosListener last;
                public static class InputWindowHandle {
                    public int displayId = 7, inputConfig;
                    public Region touchableRegion = expected;
                    IBinder window = target;
                    public IBinder getWindowToken() {
                        if (failRead) throw new IllegalStateException("unavailable");
                        return window;
                    }
                }
                static class WindowInfosListener {
                    static class DisplayInfo { }
                    static class Initial {
                        InputWindowHandle[] first;
                        DisplayInfo[] second = new DisplayInfo[0];
                    }
                    void onWindowInfosChanged(InputWindowHandle[] handles, DisplayInfo[] displays) { }
                    Initial register() {
                        registered++;
                        last = this;
                        var result = new Initial();
                        result.first = initialMatch ? new InputWindowHandle[]{new InputWindowHandle()}
                                : new InputWindowHandle[0];
                        if (publish) onWindowInfosChanged(new InputWindowHandle[]{new InputWindowHandle()}, result.second);
                        return result;
                    }
                    void unregister() { unregistered++; }
                }
                static class EventDrivenWaits {
                    enum Reason { INPUT_WINDOW_COMMIT }
                    static void noteFrameworkWait(Reason reason) { }
                }
                public static void verify() throws Exception {
                """ + scenario + "}\n"
                + RuntimeSourceFixture.methods("FrameworkInputWindowObservationSource",
                        "observeTouchableRegion", "hasTouchableRegion"));
    }
}
