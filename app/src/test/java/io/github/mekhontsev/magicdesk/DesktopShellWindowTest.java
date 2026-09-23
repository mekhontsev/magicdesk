package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopShellWindowTest {
    @Test public void keyboardNeedsDemandAndHostAcknowledgement() throws Exception {
        verify("""
                var window = host.borrowShellSurface(view, bounds, "keyboard");
                callback.accept(new Result(true));
                check(!view.keyboard, "attachment acquired keyboard");
                window.requestKeyboard();
                check(host.mKeyboardShell == window && !view.keyboard, "keyboard bypassed acknowledgement");
                host.gateReady = true;
                host.attachRequestedWindows();
                check(view.keyboard && (window.params.flags & 5) == 0, "ack did not enable child/IME");
                window.releaseKeyboard();
                check(!view.keyboard && host.mKeyboardShell == null && (window.params.flags & 5) == 5,
                        "release retained guest or Android focus");
                window.close();
                host.attachRequestedWindows();
                check(!view.keyboard, "late ack revived closed output");
                """);
    }

    @Test public void keyboardIsExclusiveBetweenShellLeasesAndYieldsToNativePanels() throws Exception {
        verify("""
                var first = host.borrowShellSurface(view, bounds, "one");
                callback.accept(new Result(true));
                var secondView = new HostedShellSurfaceView();
                var second = host.borrowShellSurface(secondView, bounds, "two");
                callback.accept(new Result(true));
                host.gateReady = true;
                first.requestKeyboard();
                second.requestKeyboard();
                check(!view.keyboard && secondView.keyboard && host.mKeyboardShell == second, "two keyboard owners");
                second.close();
                check(!secondView.keyboard && host.mKeyboardShell == null, "close retained demand");
                host.nativeRequest = true;
                first.requestKeyboard();
                check(host.mKeyboardShell == null && !view.keyboard, "external click displaced native panel");
                """);
    }

    @Test public void admissionNeedsCapabilityAndCancellationRejectsLateCompletion() throws Exception {
        verify("""
                var window = host.borrowShellSurface(view, bounds, "test");
                check(!window.ready().isDone() && adds == 0, "attached before capability");
                window.close();
                check(window.ready().isCompletedExceptionally(), "cancelled request pending");
                callback.accept(new Result(true));
                check(adds == 0 && view.closes == 1 && host.mShellWindows.isEmpty(), "late grant revived lease");
                """);
    }

    @Test public void denialAndAttachmentFailureReleaseOnlyBorrowedResources() throws Exception {
        verify("""
                var denied = host.borrowShellSurface(view, bounds, "test");
                callback.accept(new Result(false));
                check(denied.ready().isCompletedExceptionally() && adds == 0, "denial admitted");
                check(view.closes == 1 && host.mShellWindows.isEmpty(), "denial leaked");
                var failedView = new HostedShellSurfaceView();
                var failed = host.borrowShellSurface(failedView, bounds, "test");
                attachFailure = true;
                callback.accept(new Result(true));
                check(failed.ready().isCompletedExceptionally() && failedView.closes == 1, "attach failure leaked");
                """);
    }

    @Test public void placementRevokesInputBeforeRelayoutAndHostLossClosesTheLease() throws Exception {
        verify("""
                var window = host.borrowShellSurface(view, bounds, "test");
                callback.accept(new Result(true));
                check(window.ready().isDone() && adds == 1, "grant did not attach");
                check((window.params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0, "lease stole keyboard");
                window.present(new ShellBounds(40, 50, 70, 90), new HostedShellFrame());
                check(order.equals(List.of("revoke", "place")), "relayout preceded revocation");
                check(window.params.x == 40 && window.params.height == 40, "geometry lost");
                host.clearHost();
                window.close();
                check(removes == 1 && view.closes == 1 && host.mShellWindows.isEmpty(), "host cleanup not exact");
                """);
    }

    @Test public void relayoutFailureCannotLeaveActiveInputOrBorrowedOutput() throws Exception {
        verify("""
                var window = host.borrowShellSurface(view, bounds, "test");
                callback.accept(new Result(true));
                relayoutFailure = true;
                var receipt = window.present(bounds, new HostedShellFrame());
                check(receipt.isCompletedExceptionally() && view.closes == 1 && removes == 1,
                        "failed placement retained output/input");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static int adds, removes;
                static boolean attachFailure, relayoutFailure;
                static java.util.function.Consumer<Result> callback;
                static List<String> order = new ArrayList<>();
                record Result(boolean success, String message) { Result(boolean success) { this(success, "capability"); } }
                static class MagicDeskRuntime {
                    static void prepareDesktopChromeHost(int display, boolean trust, java.util.function.Consumer<Result> next) {
                        check(trust, "sparse window omitted trust"); callback = next;
                    }
                }
                static class Looper {
                    static Object myLooper() { return null; }
                    static Object getMainLooper() { return null; }
                }
                static class HostedShellFrame { }
                static class HostedShellSurfaceView {
                    int closes;
                    boolean keyboard;
                    CompletableFuture<Void> pending;
                    CompletableFuture<Void> present(HostedShellFrame frame) {
                        order.add("revoke"); pending = new CompletableFuture<>(); return pending;
                    }
                    void close() { closes++; if (pending != null) pending.completeExceptionally(new IOException("closed")); }
                    void keyboard(boolean enabled) { keyboard = enabled; }
                }
                static class WindowManager {
                    static class LayoutParams {
                        static final int FLAG_NOT_FOCUSABLE=1, FLAG_NOT_TOUCH_MODAL=2, FLAG_ALT_FOCUSABLE_IM=4,
                                FLAG_LAYOUT_IN_SCREEN=8, FLAG_LAYOUT_NO_LIMITS=16, FLAG_WATCH_OUTSIDE_TOUCH=32;
                        int x, y, width, height, flags;
                        String title;
                        CharSequence getTitle() { return title; }
                    }
                    void updateViewLayout(HostedShellSurfaceView view, LayoutParams params) {
                        order.add("place"); if (relayoutFailure) throw new IllegalStateException("lost host");
                    }
                }
                static class FocusGate { void reset() { } }
                int mDisplayId = 9;
                boolean mReleased, mHostLaunchRequested, mClearingHost;
                Object mHostActivity = new Object(), mWindowToken = new Object();
                WindowManager mWindowManager = new WindowManager();
                FocusGate mFocusGate = new FocusGate();
                Set<ShellWindow> mShellWindows = new LinkedHashSet<>();
                ShellWindow mKeyboardShell;
                boolean gateReady, nativeRequest;
                boolean nativeKeyboardRequested() { return nativeRequest; }
                boolean updateHostFocus() { return true; }
                boolean attachRequestedWindows() {
                    if (gateReady && mKeyboardShell != null) mKeyboardShell.applyKeyboard(true);
                    return true;
                }
                boolean ensureHostAndAttach() {
                    for (var lease : List.copyOf(mShellWindows)) lease.attach(); return true;
                }
                WindowManager.LayoutParams createParams(int w, int h, int flags, int x, int y, String title) {
                    var result = new WindowManager.LayoutParams();
                    result.width=w; result.height=h; result.flags=flags; result.x=x; result.y=y; result.title=title;
                    return result;
                }
                String safeTitle(String title) { return title; }
                boolean addView(HostedShellSurfaceView view, WindowManager.LayoutParams params, String title, String kind) {
                    adds++; return !attachFailure;
                }
                void removeView(HostedShellSurfaceView view, String kind) { removes++; }
                public static void verify() throws Exception {
                    var host = new Fixture();
                    var view = new HostedShellSurfaceView();
                    var bounds = new ShellBounds(10, 20, 110, 220);
                """ + scenario + "}\n"
                + RuntimeSourceFixture.methods("DesktopPanelWindowController", "borrowShellSurface", "clearHost")
                + RuntimeSourceFixture.nestedClass("DesktopPanelWindowController", "ShellWindow")
                        .replace(" implements HostedShellWindows.Window", " implements AutoCloseable")
                        .replace("@Override public java.util.concurrent.CompletableFuture", "public java.util.concurrent.CompletableFuture"), "ShellBounds");
    }
}
