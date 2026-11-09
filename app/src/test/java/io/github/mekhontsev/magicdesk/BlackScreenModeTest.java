package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class BlackScreenModeTest {
    @Test public void automationRequiresControlAndConfirmsFocusedPhonePanel() throws Exception {
        var args = new org.json.JSONObject().put("enabled", true);
        AutomationCommandArguments.check("device.set_black_screen", args);
        assertThrows(IllegalArgumentException.class, () -> AutomationCommandArguments.check(
                "device.set_black_screen", new org.json.JSONObject()));
        assertTrue(new McpAccessPolicy(java.util.Set.of("control")).allows("device.set_black_screen"));
        assertFalse(new McpAccessPolicy(java.util.Set.of("input_tests")).allows("device.set_black_screen"));
        String request = RuntimeSourceFixture.methods("ControlActivity", "requestBlackScreen");
        assertTrue(request.contains("PhoneControlPanelLauncher.openWithAndroidApi(context)"));
        assertTrue(request.indexOf("if (result.isDone()) return") < request.indexOf("sBlackScreenRequest = null"));
        assertTrue(request.indexOf("if (!enabled)") < request.indexOf("openWithAndroidApi"));
        assertTrue(request.contains("isKeyguardLocked()"));
        assertFalse(request.contains("ShellAccess"));
        String applied = RuntimeSourceFixture.methods("ControlActivity", "applyBlackScreenRequest");
        assertTrue(applied.contains("!hasWindowFocus()"));
        assertTrue(applied.contains("request.isDone()"));
        assertTrue(applied.contains("Display.DEFAULT_DISPLAY"));
        assertTrue(applied.indexOf("mBlackScreen.enter()") < applied.indexOf("mBlackScreen.confirmFrame(request)"));
        assertTrue(RuntimeSourceFixture.methods("ControlActivity", "onWindowFocusChanged")
                .contains("applyBlackScreenRequest()"));
    }

    @Test public void windowPolicyAndVolumeKeysRestoreOnlyOwnedState() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Color { static final int BLACK = 0xff000000; }
                static class View {
                    static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;
                    int visibility, color;
                    View(Object context) { }
                    Object getContext() { return null; }
                    void setVisibility(int value) { visibility = value; }
                    void setBackgroundColor(int value) { color = value; }
                    WindowInsets getRootWindowInsets() { return new WindowInsets(); }
                    android.view.ViewTreeObserver observer = new android.view.ViewTreeObserver();
                    List<Runnable> posts = new ArrayList<>();
                    boolean focused = true;
                    android.view.ViewTreeObserver getViewTreeObserver() { return observer; }
                    void post(Runnable r) { posts.add(r); }
                    boolean hasWindowFocus() { return focused; }
                    void invalidate() { }
                    void drain() { while (!posts.isEmpty()) posts.remove(0).run(); }
                }
                static class android {
                    static class view {
                        static class ViewTreeObserver {
                            interface OnDrawListener { void onDraw(); }
                            List<OnDrawListener> listeners = new ArrayList<>();
                            void addOnDrawListener(OnDrawListener l) { listeners.add(l); }
                            void removeOnDrawListener(OnDrawListener l) { listeners.remove(l); }
                            boolean isAlive() { return true; }
                            void draw() { for (var listener : List.copyOf(listeners)) listener.onDraw(); }
                        }
                    }
                }
                static class FrameLayout extends View {
                    FrameLayout(Object context) { super(context); }
                    static class LayoutParams { LayoutParams(int width, int height) { } }
                    void addView(View view, LayoutParams params) { }
                }
                static class WindowInsets {
                    static class Type {
                        static int statusBars() { return 1; }
                        static int navigationBars() { return 2; }
                        static int captionBar() { return 4; }
                        static int systemBars() { return 7; }
                        static int ime() { return 8; }
                    }
                    boolean isVisible(int type) { return type == 1 || type == 2; }
                }
                static class WindowInsetsController {
                    static final int BEHAVIOR_DEFAULT = 1, BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2;
                    int behavior = 1, visible = 3;
                    int getSystemBarsBehavior() { return behavior; }
                    void setSystemBarsBehavior(int value) { behavior = value; }
                    void hide(int types) { visible &= ~types; }
                    void show(int types) { visible |= types; }
                }
                static class WindowManager {
                    static class LayoutParams {
                        static final int FLAG_KEEP_SCREEN_ON = 128, LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3;
                        static final float BRIGHTNESS_OVERRIDE_OFF = 0;
                        int flags = 16, layoutInDisplayCutoutMode = 0;
                        float screenBrightness = -1, buttonBrightness = -1;
                        void copyFrom(LayoutParams other) {
                            flags = other.flags; layoutInDisplayCutoutMode = other.layoutInDisplayCutoutMode;
                            screenBrightness = other.screenBrightness; buttonBrightness = other.buttonBrightness;
                        }
                    }
                }
                static class Window {
                    WindowManager.LayoutParams attributes = new WindowManager.LayoutParams();
                    WindowInsetsController controller = new WindowInsetsController();
                    WindowManager.LayoutParams getAttributes() { return attributes; }
                    void setAttributes(WindowManager.LayoutParams value) { attributes = value; }
                    WindowInsetsController getInsetsController() { return controller; }
                }
                record KeyEvent(int keyCode, int action, int repeatCount, boolean canceled) {
                    static final int KEYCODE_UNKNOWN = 0, KEYCODE_VOLUME_UP = 24, KEYCODE_VOLUME_DOWN = 25,
                            KEYCODE_VOLUME_MUTE = 164, KEYCODE_BACK = 4, ACTION_DOWN = 0, ACTION_UP = 1;
                    int getKeyCode() { return keyCode; }
                    int getAction() { return action; }
                    int getRepeatCount() { return repeatCount; }
                    boolean isCanceled() { return canceled; }
                }
                public static void verify() {
                    for (int key : new int[] {24, 25, 164}) {
                        Window window = new Window();
                        View panel = new View(null);
                        BlackScreenMode mode = new BlackScreenMode(window, panel);
                        check(!mode.active(), "active before user request");
                        check(mode.black.visibility == View.GONE, "initial black overlay");
                        mode.enter();
                        mode.enter();
                        var drawn = new java.util.concurrent.CompletableFuture<Boolean>();
                        mode.confirmFrame(drawn);
                        check(!drawn.isDone(), "acknowledged before window traversal");
                        mode.root.observer.draw();
                        check(!drawn.isDone(), "completed during draw");
                        mode.root.drain();
                        check(drawn.join() && mode.root.observer.listeners.isEmpty(), "frame ack leaked listener");
                        var cancelled = new java.util.concurrent.CompletableFuture<Boolean>();
                        mode.confirmFrame(cancelled);
                        cancelled.cancel(false);
                        mode.root.drain();
                        check(mode.root.observer.listeners.isEmpty(), "cancel leaked draw listener");
                        var lost = new java.util.concurrent.CompletableFuture<Boolean>();
                        mode.confirmFrame(lost);
                        mode.root.focused = false;
                        mode.root.observer.draw();
                        mode.root.drain();
                        check(lost.isCompletedExceptionally(), "unfocused window acknowledged");
                        mode.root.focused = true;
                        check(mode.active() && mode.black.color == Color.BLACK, "not opaque black");
                        check(panel.visibility == View.INVISIBLE && mode.black.visibility == View.VISIBLE, "panel exposed");
                        check(window.attributes.flags == 144, "keep-screen-on not scoped");
                        check(window.attributes.screenBrightness == 0 && window.attributes.buttonBrightness == 0, "brightness");
                        check(window.attributes.layoutInDisplayCutoutMode == 3 && window.controller.visible == 0, "not immersive");
                        check(!mode.dispatchKeyEvent(new KeyEvent(4, 0, 0, false)), "intercepted system escape");
                        check(mode.dispatchKeyEvent(new KeyEvent(key, 1, 0, false)) && mode.active(), "unmatched key up");
                        check(mode.dispatchKeyEvent(new KeyEvent(key, 0, 0, false)) && mode.active(), "key down leaked/exited");
                        check(mode.dispatchKeyEvent(new KeyEvent(key, 0, 3, false)) && mode.active(), "repeat leaked/exited");
                        check(mode.dispatchKeyEvent(new KeyEvent(key, 1, 0, true)) && mode.active(), "cancel exited");
                        window.controller.show(3);
                        mode.hideBars();
                        check(window.controller.visible == 0, "focus return did not hide bars");
                        window.attributes.flags |= 32;
                        mode.dispatchKeyEvent(new KeyEvent(key, 0, 0, false));
                        check(mode.dispatchKeyEvent(new KeyEvent(key, 1, 0, false)), "key up leaked");
                        check(!mode.active() && panel.visibility == View.VISIBLE && mode.black.visibility == View.GONE, "exit");
                        check(window.attributes.flags == 48, "lost unrelated window flags");
                        check(window.attributes.screenBrightness == -1 && window.attributes.buttonBrightness == -1, "brightness restore");
                        check(window.attributes.layoutInDisplayCutoutMode == 0 && window.controller.visible == 3
                                && window.controller.behavior == 1, "decor restore");
                        mode.exit();
                        check(!mode.dispatchKeyEvent(new KeyEvent(key, 0, 0, false)), "inactive key intercepted");
                        window.attributes.flags |= 128;
                        window.attributes.screenBrightness = 0.4f;
                        window.controller = null;
                        mode.enter();
                        mode.dispatchKeyEvent(new KeyEvent(key, 0, 0, false));
                        mode.exit();
                        check(window.attributes.flags == 176 && window.attributes.screenBrightness == 0.4f, "previous owner lost");
                        mode.enter();
                        mode.dispatchKeyEvent(new KeyEvent(key, 1, 0, false));
                        check(mode.active(), "previous key sequence survived exit");
                        mode.exit();
                    }
                }
                """ + "static " + RuntimeSourceFixture.nestedClass("BlackScreenMode", "BlackScreenMode"));
    }

    @Test public void panelOwnsTheModeWithoutStartingAnotherActivityOrService() throws Exception {
        String header = RuntimeSourceFixture.methods("PhoneControlPanelController", "createHeader");
        assertTrue(header.contains("mActions.openBlackScreen()"));
        assertTrue(header.contains("R.string.black_screen_hint"));
        assertTrue(header.contains("title.setMaxLines(1)"));
        assertTrue(header.contains("title.setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1,"));
        assertTrue(header.indexOf("header.addView(terminals,") < header.indexOf("header.addView(blackScreen,"));
        assertTrue(header.indexOf("header.addView(blackScreen,") < header.indexOf("header.addView(settings,"));
        assertFalse(header.contains("setEnabled"));
        String enter = RuntimeSourceFixture.methods("ControlActivity", "openBlackScreen");
        assertTrue(enter.contains("mBlackScreen.enter()"));
        assertFalse(enter.contains("startActivity"));
        assertFalse(enter.contains("ShellAccess"));
        for (String lifecycle : new String[] {"onPause", "onDestroy"}) {
            String method = RuntimeSourceFixture.methods("ControlActivity", lifecycle);
            assertTrue(method.contains("mBlackScreen.exit()"));
            assertTrue(method.indexOf("mBlackScreen.exit()") < method.indexOf("super."));
        }
        for (String dispatch : new String[] {"dispatchTouchEvent", "dispatchGenericMotionEvent"}) {
            String method = RuntimeSourceFixture.methods("ControlActivity", dispatch);
            assertTrue(method.contains("mBlackScreen.active() || super."));
        }
        String mode = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "BlackScreenMode.java"));
        for (String forbidden : new String[] {"ShellAccess", "PlatformDrivers", "PowerManager", "Settings.System",
                "startService", "postDelayed", "new Thread", "FLAG_NOT_TOUCHABLE", "UiAppearance"}) {
            assertFalse(forbidden, mode.contains(forbidden));
        }
    }
}
