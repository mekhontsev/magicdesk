package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Matrix;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** An ordinary, independently placed window onto another logical display. */
public final class DisplayViewerActivity extends Activity implements SurfaceHolder.Callback,
        DisplayPresentations.Listener, DisplayManager.DisplayListener {
    private static final String SESSION = "display_viewer_session";
    private final DisplayViewerConnection mConnection = new DisplayViewerConnection(this::connectionFailed);
    private DisplayPresentations.Session mSession;
    private DisplayManager mDisplays;
    private DesktopUiFactory mUi;
    private LinearLayout mToolbar;
    private Button mSourceButton;
    private ImageButton mPrevious;
    private ImageButton mInput;
    private ImageButton mClose;
    private TextView mError;
    private FrameLayout mFrame;
    private SurfaceView mSurface;
    private long mRenderedBinding = -1;
    private DisplayViewport mViewport;
    private boolean mAttached;
    private boolean mTouching;
    private boolean mGeometryRefreshPending;

    static Intent createIntent(Context context, String id) {
        return new Intent(context, DisplayViewerActivity.class).putExtra(SESSION, id);
    }

    private void connectionFailed(Throwable error) {
        mAttached = false;
        mTouching = false;
        if (mSession != null && !isDestroyed()) DisplayPresentations.failed(mSession, error);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSession = DisplayPresentations.find(getIntent().getStringExtra(SESSION));
        if (mSession == null || getDisplay() == null
                || getDisplay().getDisplayId() != mSession.output.id) {
            if (mSession != null) DisplayPresentations.park(mSession);
            finish();
            return;
        }
        mSession.listener = this;
        mDisplays = getSystemService(DisplayManager.class);
        mDisplays.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
        BuiltInWindowRegistry.register(this);
        DesktopTaskDescription.apply(this, R.string.display_viewer, R.drawable.ic_show_desktop);
        mUi = new DesktopUiFactory(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        SystemBarInsets.addToPadding(root);
        mToolbar = new LinearLayout(this);
        mToolbar.setGravity(Gravity.CENTER_VERTICAL);
        mToolbar.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);
        mPrevious = action(R.drawable.ic_file_back, R.string.display_previous,
                () -> DisplayPresentations.previous(mSession));
        mSourceButton = new Button(this);
        mSourceButton.setSingleLine(true);
        mSourceButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mSourceButton.setOnClickListener(v -> chooseSource());
        mToolbar.addView(mSourceButton, new LinearLayout.LayoutParams(0, mUi.dp(48), 1));
        mInput = action(R.drawable.ic_keyboard, R.string.display_control, () -> {
            mInput.setEnabled(false);
            DisplayPresentations.controlInput(mSession, result -> runOnUiThread(() -> {
                if (isDestroyed()) return;
                mInput.setEnabled(mSession.ready);
                if (!result.success) android.widget.Toast.makeText(this, result.message,
                        android.widget.Toast.LENGTH_LONG).show();
            }));
        });
        action(R.drawable.ic_show_desktop, R.string.action_open_fullscreen, () -> {
            DisplayPresentations.setFullscreen(mSession, !mSession.fullscreen);
        });
        mClose = action(R.drawable.ic_close, R.string.action_close_window, () -> DisplayPresentations.park(mSession));
        root.addView(mToolbar, new LinearLayout.LayoutParams(-1, -2));
        mFrame = new FrameLayout(this);
        mFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit());
        mError = new TextView(this);
        mError.setTextColor(Color.WHITE);
        mError.setGravity(Gravity.CENTER);
        mError.setPadding(mUi.dp(24), mUi.dp(24), mUi.dp(24), mUi.dp(24));
        mFrame.addView(mError, new FrameLayout.LayoutParams(-1, -1));
        root.addView(mFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                    if (mSession.fullscreen) DisplayPresentations.setFullscreen(mSession, false);
                    else DisplayPresentations.park(mSession);
                });
        changed();
    }

    private ImageButton action(int icon, int label, Runnable operation) {
        final ImageButton button = mUi.menuIconButton(icon, label);
        button.setOnClickListener(v -> operation.run());
        mToolbar.addView(button, new LinearLayout.LayoutParams(mUi.dp(48), mUi.dp(48)));
        return button;
    }

    @Override public void changed() {
        if (mSession.closed) { finishAndRemoveTask(); return; }
        mSourceButton.setText(mSession.source.name + " [" + mSession.source.id + "]");
        mPrevious.setEnabled(mSession.change == null && !mSession.history.isEmpty());
        mInput.setEnabled(mSession.ready && mSession.change == null);
        mSourceButton.setEnabled(mSession.change == null);
        final String closeLabel = getString(DisplayPresentationMode.forSource(mSession.source)
                == DisplayPresentationMode.DIRECT ? R.string.display_park : R.string.action_close_window);
        mClose.setContentDescription(closeLabel);
        mClose.setTooltipText(closeLabel);
        mError.setText(mSession.error);
        mError.setVisibility(mSession.error.isEmpty() ? View.GONE : View.VISIBLE);
        if (mRenderedBinding != mSession.bindingGeneration) {
            mRenderedBinding = mSession.bindingGeneration;
            mAttached = false;
            mTouching = false;
            mConnection.close();
            createSurface();
            fit();
            attachWhenSized();
        }
        renderChrome();
        refreshSourceGeometry();
    }

    private void createSurface() {
        // setSurface schedules compositor work. Its return does not mean the
        // previous producer disconnected, so a different source gets its own
        // BufferQueue rather than racing to reconnect the previous consumer.
        if (mSurface != null) {
            mSurface.getHolder().removeCallback(this);
            mFrame.removeView(mSurface);
        }
        mSurface = new SurfaceView(this);
        mSurface.setFocusableInTouchMode(true);
        mSurface.setContentDescription(getString(R.string.display_viewer));
        mSurface.getHolder().setFixedSize(mSession.source.width, mSession.source.height);
        mSurface.getHolder().addCallback(this);
        mSurface.setOnTouchListener((v, event) -> forwardMotion(event));
        mSurface.setOnGenericMotionListener((v, event) -> forwardMotion(event));
        mFrame.addView(mSurface, 0, new FrameLayout.LayoutParams(1, 1));
    }

    @Override public void detach(BuiltInWindowLauncher.Callback completion) {
        mAttached = false;
        mTouching = false;
        mConnection.close(completion);
    }

    @Override public void show(BuiltInWindowLauncher.Callback completion) {
        if (mSession.closed || isFinishing() || getDisplay() == null
                || getDisplay().getDisplayId() != mSession.output.id) {
            completion.onComplete(new IllegalStateException("viewer window is unavailable"));
            return;
        }
        // Ordinary AppTask activation does not alter placement. Managed viewers
        // use the same focus gateway as taskbar and Alt+Tab.
        if (DesktopRuntimeBridge.hasWorkspace(mSession.output.id)) {
            MagicDeskRuntime.focusDesktopTask(mSession.output.id, getTaskId(), result ->
                    completion.onComplete(result.success ? null : new IllegalStateException(result.message)));
            return;
        }
        try {
            for (final android.app.ActivityManager.AppTask task
                    : getSystemService(android.app.ActivityManager.class).getAppTasks()) {
                if (task.getTaskInfo().taskId == getTaskId()) {
                    task.moveToFront();
                    completion.onComplete(null);
                    return;
                }
            }
            completion.onComplete(new IllegalStateException("viewer task is unavailable"));
        } catch (RuntimeException error) { completion.onComplete(error); }
    }

    private void renderChrome() {
        mToolbar.setVisibility(mSession.fullscreen ? View.GONE : View.VISIBLE);
        final WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (mSession.fullscreen) insets.hide(WindowInsets.Type.systemBars());
            else insets.show(WindowInsets.Type.systemBars());
        }
    }

    private void fit() {
        if (mSurface == null || mFrame.getWidth() <= 0 || mFrame.getHeight() <= 0) return;
        final DisplayViewport viewport = DisplayViewport.fit(mSession.source.width,
                mSession.source.height, mFrame.getWidth(), mFrame.getHeight());
        mViewport = viewport;
        final FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) mSurface.getLayoutParams();
        if (old.width == viewport.width && old.height == viewport.height
                && old.leftMargin == viewport.left && old.topMargin == viewport.top) return;
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(viewport.width, viewport.height);
        params.leftMargin = viewport.left;
        params.topMargin = viewport.top;
        mSurface.setLayoutParams(params);
    }

    private void attachWhenSized() {
        if (mAttached || mSession.closed || !mSurface.getHolder().getSurface().isValid()) return;
        final android.graphics.Rect size = mSurface.getHolder().getSurfaceFrame();
        final DesktopDisplayInfo source = mSession.source;
        if (size.width() != source.width || size.height() != source.height) return;
        mAttached = true;
        mConnection.attach(source, mSession.output, mSurface.getHolder().getSurface(), mSurface.getSurfaceControl(), error -> {
            if (isDestroyed() || mSession.closed || !source.uniqueId.equals(mSession.source.uniqueId)) return;
            if (error == null) {
                DisplayPresentations.attached(mSession);
            } else { mAttached = false; DisplayPresentations.failed(mSession, error); }
        });
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { attachWhenSized(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        attachWhenSized();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        mAttached = false;
        DisplayPresentations.detached(mSession);
        mTouching = false;
        mConnection.close();
    }

    private boolean forwardMotion(MotionEvent event) {
        if (!mAttached || !mSession.ready || mViewport == null
                || mSurface.getWidth() <= 0 || mSurface.getHeight() <= 0) return false;
        final int action = event.getActionMasked();
        final boolean inside = mViewport.contains(event.getX() + mViewport.left, event.getY() + mViewport.top);
        if (!mTouching && !inside) return false;
        if (action == MotionEvent.ACTION_DOWN) { mTouching = true; mSurface.requestFocus(); }
        final MotionEvent copy = MotionEvent.obtain(event);
        final Matrix transform = new Matrix();
        transform.setScale(mViewport.sourceScaleX(), mViewport.sourceScaleY());
        copy.transform(transform);
        try { mConnection.motion(copy); } finally { copy.recycle(); }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) mTouching = false;
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (mSession != null && event.getKeyCode() == KeyEvent.KEYCODE_TAB
                && event.isCtrlPressed() && event.isAltPressed()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                DisplayPresentations.previous(mSession);
            }
            return true;
        }
        if (mSession != null && mSession.ready && mSurface.hasFocus()
                && event.getKeyCode() != KeyEvent.KEYCODE_BACK
                && event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP
                && event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) {
            mConnection.key(event);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void chooseSource() {
        DisplayOperations.readDisplays((displays, error) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (error != null) { DisplayPresentations.failed(mSession, new IllegalStateException(error)); return; }
            final var sources = java.util.Arrays.stream(displays)
                    .filter(d -> d.id != mSession.output.id).toList();
            new AlertDialog.Builder(this).setTitle(R.string.display_viewer_source)
                    .setItems(sources.stream().map(d -> d.name + " [" + d.id + "]").toArray(String[]::new),
                            (dialog, index) -> DisplayPresentations.select(mSession, sources.get(index).id))
                    .setNegativeButton(android.R.string.cancel, null).show();
        }));
    }

    @Override public void onDisplayAdded(int displayId) { }
    @Override public void onDisplayChanged(int displayId) {
        if (mSession != null && displayId == mSession.source.id) refreshSourceGeometry();
    }

    private void refreshSourceGeometry() {
        if (mSession.closed || mSession.change != null || mGeometryRefreshPending) return;
        final android.view.Display display = mDisplays.getDisplay(mSession.source.id);
        if (display == null) return;
        final android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        createDisplayContext(display).getDisplay().getRealMetrics(metrics);
        if (metrics.widthPixels == mSession.source.width && metrics.heightPixels == mSession.source.height
                && metrics.densityDpi == mSession.source.densityDpi) return;
        // Refresh only after an actual geometry change, not for frame/rate or
        // power notifications. Source identity and dimensions come from the
        // shared privileged catalog; no task observer or periodic query.
        mGeometryRefreshPending = true;
        DisplayOperations.readDisplays((displays, error) -> runOnUiThread(() -> {
            mGeometryRefreshPending = false;
            if (isDestroyed() || mSession.closed) return;
            if (error != null) {
                DisplayPresentations.failed(mSession, new IllegalStateException(error));
                return;
            }
            for (DesktopDisplayInfo source : displays) {
                if (source.id == mSession.source.id) {
                    if (!source.uniqueId.equals(mSession.source.uniqueId)) DisplayPresentations.park(mSession);
                    else DisplayPresentations.updateGeometry(mSession, source);
                    return;
                }
            }
            DisplayPresentations.park(mSession);
        }));
    }
    @Override public void onDisplayRemoved(int displayId) {
        if (mSession != null && (displayId == mSession.source.id || displayId == mSession.output.id)) {
            DisplayPresentations.park(mSession);
        }
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        verifyOutput();
    }

    @Override protected void onResume() {
        super.onResume();
        verifyOutput();
    }

    @Override protected void onStart() {
        super.onStart();
        if (mSession != null) DisplayPresentations.visibilityChanged(mSession, true);
    }

    @Override protected void onStop() {
        if (mSession != null) DisplayPresentations.visibilityChanged(mSession, false);
        super.onStop();
    }

    private void verifyOutput() {
        if (mSession != null && (getDisplay() == null
                || getDisplay().getDisplayId() != mSession.output.id)) DisplayPresentations.park(mSession);
    }

    @Override protected void onDestroy() {
        mConnection.close();
        if (mDisplays != null) mDisplays.unregisterDisplayListener(this);
        if (mSession != null && mSession.listener == this) {
            mSession.listener = null;
            if (!isChangingConfigurations()) DisplayPresentations.park(mSession);
        }
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }
}
