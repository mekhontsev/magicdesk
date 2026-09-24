package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Guest text-input-v3 through the production Activity on an explicitly prepared test display. */
public final class HostedGuestEditorInstrumentation extends Instrumentation implements WaylandSessions.Listener {
    private String command;
    private int displayId;
    private int entryY;
    private boolean actualIme;
    private boolean correction;
    private WaylandSessions.Session session;
    private Activity activity;
    private HostedSurfaceView surface;
    private HostedTextInputConnection connection;
    private Runnable observation;
    private String readbackTrace = "";
    private final android.os.Handler events = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        command = args == null ? null : args.getString("command");
        displayId = args == null ? -1 : Integer.parseInt(args.getString("display", "-1"));
        actualIme = args != null && Boolean.parseBoolean(args.getString("ime", "false"));
        correction = args == null || Boolean.parseBoolean(args.getString("correction", "true"));
        entryY = args == null ? 70 : Integer.parseInt(args.getString("entryY", "70"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            exercise();
            result.putString("guest_editor", "PASS guest composition, Unicode commit, field switch, private PIN, caret geometry, "
                    + (actualIme ? "Android IME transport and keyboard insets" : "controlled IME insets")
                    + ", constrained dialog; correction=" + (correction ? "PASS" : "NOT_TESTED"));
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError error) {
            result.putString("guest_editor", "FAIL " + error);
            result.putString("trace", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    @SuppressWarnings("deprecation")
    private void exercise() throws Exception {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("GTK guest launch command required");
        var context = getTargetContext();
        var display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (displayId <= 0 || display == null || DesktopRuntimeBridge.hasWorkspace(displayId))
            throw new IllegalArgumentException("An independent non-phone test display is required");
        var awake = context.getSystemService(PowerManager.class).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "MagicDesk:GuestEditorFixture");
        awake.acquire(180000);
        ShellInputRoutingHandle routing = null;
        try {
            if (actualIme) routing = ShellAccess.openInputRouting(displayId, false, true);
            try {
                runOnMainSync(() -> {
                    session = WaylandSessions.start(context, "GTK editor fixture", command, "", DesktopExecBackend.TERMUX, "");
                    session.listen(this);
                });
                await("GTK toplevel", () -> session.windows().stream().anyMatch(item -> item.mapped()));
                long window = session.windows().stream().filter(item -> item.mapped()).findFirst().orElseThrow().id();
                var options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
                activity = startActivitySync(WaylandActivity.windowIntent(context, session, window), options.toBundle());
                runOnMainSync(() -> activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(this::check));
                await("focused host", () -> {
                    surface = findSurface(activity.getWindow().getDecorView());
                    return surface != null && surface.hasWindowFocus() && surface.geometry().contentWidth() > 0;
                });
                runOnMainSync(() -> {
                    // Fixture's first entry in logical surface coordinates at density 1.
                    long now = android.os.SystemClock.uptimeMillis();
                    for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
                        var event = android.view.MotionEvent.obtain(now, now, action, 100, entryY, 0);
                        surface.dispatchTouchEvent(event); event.recycle();
                    }
                });
                await("GTK text context", () -> {
                    var info = new EditorInfo();
                    connection = observeConnection(info);
                    return connection != null && connection.sameEditor() && (!actualIme || HostedFixtureIme.connection() != null)
                            && connection.getTextBeforeCursor(100, 0).length() == 0;
                });
                if (actualIme) {
                    runOnMainSync(() -> {
                        HostedFixtureIme.changed = this::check;
                        context.getSystemService(android.view.inputmethod.InputMethodManager.class)
                                .showSoftInput(surface, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    });
                    await("real keyboard connection and visible IME", () -> HostedFixtureIme.connection() != null
                            && surface.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()));
                }
                write(input -> {
                    require(input.setComposingText("draft", 1), "GTK preedit");
                });
                await("Android composing snapshot", () -> before().equals("draft"));
                write(input -> {
                    require(input.commitText("hello \u0416\ud83d\ude00", 1), "Unicode commit");
                    require(input.finishComposingText(), "finish composition");
                });
                await("GTK committed surrounding text", () -> acknowledged("hello \u0416\ud83d\ude00"));
                if (correction) {
                    write(input -> require(input.deleteSurroundingTextInCodePoints(2, 0), "Unicode correction deletion"));
                    await("guest correction acknowledged", () -> acknowledged("hello "));
                }
                String expectedText = correction ? "hello world" : "hello \u0416\ud83d\ude00world";
                write(input -> input.commitText("world", 1));
                await("guest append", () -> acknowledged(expectedText));
                await("guest caret", () -> connection.cursorInfo() != null);
                runOnMainSync(() -> {
                    require(connection.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR,
                            InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER), "caret subscription");
                    var caret = connection.cursorInfo();
                    float[] position = {caret.getInsertionMarkerHorizontal(), caret.getInsertionMarkerTop()};
                    caret.getMatrix().mapPoints(position);
                    var geometry = surface.geometry();
                    require(position[0] >= geometry.left() && position[0] <= geometry.right()
                            && position[1] >= geometry.top() && position[1] <= geometry.bottom(), "caret inside rendered output");
                });
                HostedTextInputConnection previous = connection;
                if (!actualIme) verifyOcclusion(expectedText);
                tab();
                awaitEditor(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_MASK_VARIATION);
                runOnMainSync(() -> {
                    require(!previous.commitText("stale", 1), "old editor cannot insert into email");
                });
                write(input -> input.commitText("gtk@example.test", 1));
                await("GTK email", () -> acknowledged("gtk@example.test"));
                tab();
                awaitEditor(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_MASK_CLASS);
                runOnMainSync(() -> {
                    var info = new EditorInfo();
                    connection = observeConnection(info);
                    require((info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0, "PIN learning disabled");
                });
                write(input -> input.commitText("1234", 1));
                await("private field caret", () -> connection.cursorInfo() != null);
                runOnMainSync(() -> require(connection.getTextBeforeCursor(50, 0).length() == 0, "PIN contents not exposed"));
                // The GTK fixture has four buttons after PIN; the last opens the dialog.
                for (int i = 0; i < 4; i++) tab();
                key(KeyEvent.KEYCODE_SPACE);
                await("GTK dependent dialog", () -> session.windows().stream().anyMatch(item -> item.mapped() && item.parent() == window));
                await("small dialog host", () -> session.windows().stream().filter(item -> item.mapped() && item.parent() == window)
                        .anyMatch(item -> session.hostTaskId(item.id()) >= 0 && item.width() < 800 && item.height() < 400));
            } finally {
                runOnMainSync(() -> {
                    observation = null;
                    HostedFixtureIme.changed = null;
                    if (session != null) { session.unlisten(this); session.close(); }
                    if (activity != null) activity.finishAndRemoveTask();
                });
            }
        } finally {
            try { if (routing != null) routing.close(); }
            finally { if (awake.isHeld()) awake.release(); }
        }
    }

    private HostedTextInputConnection observeConnection(EditorInfo info) {
        if (!actualIme) return (HostedTextInputConnection) surface.onCreateInputConnection(info);
        // Test-only inspection of our own View, without replacing Android's active connection.
        try {
            var field = HostedSurfaceView.class.getDeclaredField("textConnection");
            field.setAccessible(true);
            if (HostedFixtureIme.active != null && HostedFixtureIme.active.getCurrentInputEditorInfo() != null) {
                var current = HostedFixtureIme.active.getCurrentInputEditorInfo();
                info.inputType = current.inputType;
                info.imeOptions = current.imeOptions;
                info.initialSelStart = current.initialSelStart;
            }
            return (HostedTextInputConnection) field.get(surface);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private String before() {
        if (actualIme) connection = observeConnection(new EditorInfo());
        return connection == null ? "" : connection.getTextBeforeCursor(100, 0).toString();
    }
    private boolean acknowledged(String text) {
        String host = before();
        readbackTrace = "host=" + host;
        return host.equals(text);
    }
    private InputConnection writer() {
        var result = actualIme ? HostedFixtureIme.connection() : connection;
        if (result == null) throw new IllegalStateException("Keyboard connection is not ready");
        return result;
    }
    private void write(java.util.function.Consumer<InputConnection> action) {
        if (!actualIme) { runOnMainSync(() -> action.accept(connection)); return; }
        InputConnection[] remote = new InputConnection[1];
        runOnMainSync(() -> remote[0] = writer());
        action.accept(remote[0]);
        // EVENT_WAIT: remote InputConnection readback drains preceding IME edits. Android
        // bounds the reply; null fails the stage instead of assuming delivery or polling.
        var readback = remote[0].getTextBeforeCursor(100, 0);
        runOnMainSync(() -> readbackTrace = "remote=" + readback + "; host=" + before()
                + "; same=" + connection.sameEditor() + "; editable=" + connection.getEditable());
        require(readback != null, "IME edit readback");
    }
    private void verifyOcclusion(String expectedText) throws Exception {
        var content = (ViewGroup) activity.findViewById(android.R.id.content);
        var root = content.getChildAt(0);
        var insets = activity.getWindow().getDecorView().getRootWindowInsets();
        int height = surface.getHeight();
        // A deterministic Android IME-inset delivery, independent of the installed keyboard's display policy.
        runOnMainSync(() -> root.dispatchApplyWindowInsets(new android.view.WindowInsets.Builder(insets)
                .setInsets(android.view.WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, 240))
                .setVisible(android.view.WindowInsets.Type.ime(), true).build()));
        try {
            await("IME-safe GTK output below " + height, () -> surface.getHeight() < height
                    && surface.geometry().contentHeight() == surface.getHeight());
            runOnMainSync(() -> {
                var caret = connection.cursorInfo();
                require(caret != null, "caret retained after IME resize");
                float[] point = {caret.getInsertionMarkerHorizontal(), caret.getInsertionMarkerBottom()};
                caret.getMatrix().mapPoints(point);
                require(point[1] <= surface.geometry().bottom(), "caret stays in IME-safe output");
                require(before().equals(expectedText), "IME resize retains editor text");
            });
        } finally { runOnMainSync(() -> root.dispatchApplyWindowInsets(insets)); }
        await("IME dismissal layout", () -> surface.getHeight() == height
                && surface.geometry().contentHeight() == height);
    }
    private void tab() { key(KeyEvent.KEYCODE_TAB); }
    private void key(int code) {
        runOnMainSync(() -> {
            surface.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            surface.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        });
    }
    private void awaitEditor(int expected, int mask) throws Exception {
        await("GTK field purpose " + expected, () -> {
            var info = new EditorInfo();
            var next = observeConnection(info);
            if (next == null || (info.inputType & mask) != expected) return false;
            if (actualIme && (HostedFixtureIme.active == null
                    || (HostedFixtureIme.active.getCurrentInputEditorInfo().inputType & mask) != expected)) return false;
            connection = next; return true;
        });
    }
    private void await(String event, BooleanSupplier predicate) throws Exception {
        var ready = new CompletableFuture<Void>();
        runOnMainSync(() -> {
            observation = () -> {
                try {
                    if (session.stopped()) throw new IllegalStateException(session.error());
                    if (predicate.getAsBoolean()) ready.complete(null);
                } catch (RuntimeException | AssertionError error) { ready.completeExceptionally(error); }
            };
            check();
        });
        // EVENT_WAIT: guest protocol or Android layout callback; timeout fails the exact stage.
        try { ready.get(30, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException error) {
            throw new IllegalStateException("Missing " + event + "; " + readbackTrace + "; session=" + session.state() + "/" + session.error()
                    + "; host=" + (surface == null ? "none" : surface.getWidth() + "x" + surface.getHeight()
                    + "/focus=" + surface.hasWindowFocus() + "/" + surface.geometry()), error);
        }
        finally { runOnMainSync(() -> observation = null); }
    }
    private void check() {
        // Observe after the same event reaches the production host, regardless of listener order.
        events.post(() -> { if (observation != null) observation.run(); });
    }
    @Override public void changed() { check(); }
    @Override public void textInputChanged(long output) { check(); }
    @Override public void frame(long output, int width, int height) { check(); }
    private static HostedSurfaceView findSurface(View view) {
        if (view instanceof HostedSurfaceView surface) return surface;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            var found = findSurface(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
