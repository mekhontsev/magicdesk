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
    private final KeyboardShortcutStateMachine mDisplayKeys = new KeyboardShortcutStateMachine();
    private DisplayManager mDisplays;
    private DesktopUiFactory mUi;
    private LinearLayout mToolbar;
    private Button mSourceButton;
    private ImageButton mPrevious;
    private ImageButton mInput;
    private ImageButton mClose;
    private ImageButton mFullscreen;
    private Button mChooseSource;
    private TextView mError;
    private FrameLayout mFrame;
    private SurfaceView mSurface;
    private long mRenderedBinding = -1;
    private DisplayViewport mViewport;
    private boolean mAttached;
    private boolean mTouching;
    private boolean mGeometryRefreshPending;
    private boolean mStarted;
    private int mSelectionGeneration;
    private AlertDialog mSourceDialog;

    static Intent createIntent(Context context) {
        return new Intent(context, DisplayViewerActivity.class);
    }

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
        final String sessionId = savedInstanceState == null ? getIntent().getStringExtra(SESSION)
                : savedInstanceState.getString(SESSION);
        mSession = DisplayPresentations.find(sessionId);
        if (getDisplay() == null || getIntent().hasExtra(SESSION) && mSession == null
                || mSession != null && getDisplay().getDisplayId() != mSession.output.id) {
            if (mSession != null) DisplayPresentations.detach(mSession);
            finish();
            return;
        }
        if (mSession != null) {
            mSession.listener = this;
            mSession.taskId = getTaskId();
        }
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
        mFullscreen = action(R.drawable.ic_show_desktop, R.string.action_open_fullscreen, () -> {
            DisplayPresentations.setFullscreen(mSession, !mSession.fullscreen);
        });
        mClose = action(R.drawable.ic_close, R.string.action_close, this::finishAndRemoveTask);
        root.addView(mToolbar, new LinearLayout.LayoutParams(-1, -2));
        mFrame = new FrameLayout(this);
        mFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit());
        mError = new TextView(this);
        mError.setTextColor(Color.WHITE);
        mError.setGravity(Gravity.CENTER);
        mError.setPadding(mUi.dp(24), mUi.dp(24), mUi.dp(24), mUi.dp(24));
        mFrame.addView(mError, new FrameLayout.LayoutParams(-1, -1));
        mChooseSource = new Button(this);
        mChooseSource.setText(R.string.display_viewer_source);
        mChooseSource.setOnClickListener(v -> chooseSource());
        mFrame.addView(mChooseSource, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(mFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                    if (mSession != null && mSession.fullscreen) DisplayPresentations.setFullscreen(mSession, false);
                    else finishAndRemoveTask();
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
        if (mSession != null && mSession.closed) {
            if (mSession.outputAttachment) { finishAndRemoveTask(); return; }
            mSession.listener = null;
            mSession = null;
            mSelectionGeneration++;
            mRenderedBinding = -1;
            mAttached = false;
            mTouching = false;
            mConnection.close();
            removeSurface();
        }
        final boolean bound = mSession != null;
        mChooseSource.setVisibility(bound ? View.GONE : View.VISIBLE);
        mSourceButton.setVisibility(bound ? View.VISIBLE : View.INVISIBLE);
        mPrevious.setEnabled(bound && mSession.change == null && !mSession.history.isEmpty());
        mFullscreen.setEnabled(bound);
        mInput.setVisibility(bound && mSession.outputAttachment ? View.VISIBLE : View.GONE);
        renderChrome();
        if (!bound) {
            mError.setVisibility(View.GONE);
            return;
        }
        mSourceButton.setText(mSession.source.name + " [" + mSession.source.id + "]");
        mPrevious.setEnabled(mSession.change == null && !mSession.history.isEmpty());
        mInput.setEnabled(mSession.ready && mSession.change == null);
        mSourceButton.setEnabled(mSession.change == null);
        final String closeLabel = getString(mSession.outputAttachment
                ? R.string.display_stop_showing : R.string.action_close);
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
        refreshSourceGeometry();
    }

    private void createSurface() {
        // setSurface schedules compositor work. Its return does not mean the
        // previous producer disconnected, so a different source gets its own
        // BufferQueue rather than racing to reconnect the previous consumer.
        removeSurface();
        mSurface = new SurfaceView(this);
        mSurface.setSecure(mSession.source.protectedContent());
        mSurface.setFocusableInTouchMode(true);
        mSurface.setContentDescription(getString(R.string.display_viewer));
        mSurface.getHolder().setFixedSize(mSession.source.width, mSession.source.height);
        mSurface.getHolder().addCallback(this);
        mSurface.setOnTouchListener((v, event) -> forwardMotion(event));
        mSurface.setOnGenericMotionListener((v, event) -> forwardMotion(event));
        mFrame.addView(mSurface, 0, new FrameLayout.LayoutParams(1, 1));
    }

    private void removeSurface() {
        if (mSurface == null) return;
        mSurface.getHolder().removeCallback(this);
        mFrame.removeView(mSurface);
        mSurface = null;
    }

    @Override public void detach(BuiltInWindowLauncher.Callback completion) {
        mAttached = false;
        mTouching = false;
        mConnection.close(completion);
    }

    @Override public void show(BuiltInWindowLauncher.Callback completion) {
        if (mSession == null || mSession.closed || isFinishing() || getDisplay() == null
                || getDisplay().getDisplayId() != mSession.output.id) {
            completion.onComplete(new IllegalStateException("viewer window is unavailable"));
            return;
        }
        // Viewer stays independent even when its output hosts a Desktop.
        // AppTask activation preserves its placement and presentation binding.
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
        final boolean fullscreen = mSession != null && mSession.fullscreen;
        mToolbar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        final WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (fullscreen) insets.hide(WindowInsets.Type.systemBars());
            else insets.show(WindowInsets.Type.systemBars());
        }
    }

    private void fit() {
        if (mSession == null || mSurface == null || mFrame.getWidth() <= 0 || mFrame.getHeight() <= 0) return;
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
        if (mSession == null || mSurface == null || mAttached || mSession.closed
                || !mSurface.getHolder().getSurface().isValid()) return;
        final android.graphics.Rect size = mSurface.getHolder().getSurfaceFrame();
        final DesktopDisplayInfo source = mSession.source;
        if (size.width() != source.width || size.height() != source.height) return;
        mAttached = true;
        final DisplayPresentations.Session session = mSession;
        final DisplayPresentationMode mode = session.outputAttachment
                ? DisplayPresentationMode.forSource(source) : DisplayPresentationMode.MIRROR;
        mConnection.attach(source, session.output, mode,
                mSurface.getHolder().getSurface(), mSurface.getSurfaceControl(), error -> {
            if (isDestroyed() || session != mSession || session.closed
                    || !source.uniqueId.equals(session.source.uniqueId)) return;
            if (error == null) {
                DisplayPresentations.attached(mSession);
            } else { mAttached = false; DisplayPresentations.failed(mSession, error); }
        });
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (mSurface != null && holder == mSurface.getHolder()) attachWhenSized();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mSurface != null && holder == mSurface.getHolder()) attachWhenSized();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (mSurface == null || holder != mSurface.getHolder()) return;
        mAttached = false;
        if (mSession != null) DisplayPresentations.detached(mSession);
        mTouching = false;
        mConnection.close();
    }

    private boolean forwardMotion(MotionEvent event) {
        if (mSession == null || mSurface == null || !mAttached || !mSession.ready || mViewport == null
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
        final var shortcut = mDisplayKeys.accept(event.getKeyCode(), event.getAction() == KeyEvent.ACTION_DOWN,
                event.getRepeatCount(), event.isCtrlPressed(), event.isAltPressed(),
                event.isShiftPressed(), event.isMetaPressed(), false);
        switch (shortcut.action) {
            case DISPLAY_FORWARD, DISPLAY_REVERSE -> DisplaySwitchController.advance(getDisplay().getDisplayId(),
                    this, shortcut.action == KeyboardShortcutStateMachine.Action.DISPLAY_REVERSE);
            case DISPLAY_COMMIT -> DisplaySwitchController.commit();
            case DISPLAY_CANCEL -> DisplaySwitchController.cancel();
            default -> { }
        }
        if (shortcut.consumed) return true;
        if (mSession != null && mSession.ready && mSurface != null && mSurface.hasFocus()
                && event.getKeyCode() != KeyEvent.KEYCODE_BACK
                && event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP
                && event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) {
            mConnection.key(event);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void chooseSource() {
        if (mSourceDialog != null) return;
        final int generation = ++mSelectionGeneration;
        DisplayOperations.readDisplays((displays, error) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || generation != mSelectionGeneration || getDisplay() == null) return;
            if (error != null) { showSelectionError(error); return; }
            final var sources = java.util.Arrays.stream(displays)
                    .filter(d -> d.id != getDisplay().getDisplayId()).toList();
            if (sources.isEmpty()) { showSelectionError(getString(R.string.display_viewer_no_sources)); return; }
            mSourceDialog = new AlertDialog.Builder(this).setTitle(R.string.display_viewer_source)
                    .setItems(sources.stream().map(d -> d.name + " [" + d.id + "]").toArray(String[]::new),
                            (dialog, index) -> bindSource(sources.get(index)))
                    .setNegativeButton(android.R.string.cancel, null).show();
            mSourceDialog.setOnDismissListener(dialog -> mSourceDialog = null);
        }));
    }

    private void bindSource(DesktopDisplayInfo requested) {
        final int generation = ++mSelectionGeneration;
        DisplayOperations.readDisplays((displays, error) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || generation != mSelectionGeneration || getDisplay() == null) return;
            if (error != null) { showSelectionError(error); return; }
            DesktopDisplayInfo source = null, output = null;
            for (DesktopDisplayInfo display : displays) {
                if (display.id == requested.id && display.uniqueId.equals(requested.uniqueId)) source = display;
                if (display.id == getDisplay().getDisplayId()) output = display;
            }
            if (source == null || output == null) { showSelectionError("Display is no longer available"); return; }
            try {
                if (mSession == null) {
                    mSession = DisplayPresentations.mirror(source, output);
                    mSession.listener = this;
                    mSession.taskId = getTaskId();
                    DisplayPresentations.visibilityChanged(mSession, mStarted);
                    changed();
                } else {
                    DisplayPresentations.select(mSession, source.id, source.uniqueId, failure -> {
                        if (!isDestroyed() && failure != null) showSelectionError(ShellAccess.usefulMessage(failure));
                    });
                }
            } catch (RuntimeException failure) { showSelectionError(ShellAccess.usefulMessage(failure)); }
        }));
    }

    private void showSelectionError(String error) {
        android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show();
    }

    @Override public void onDisplayAdded(int displayId) { }
    @Override public void onDisplayChanged(int displayId) {
        if (mSession != null && displayId == mSession.source.id) refreshSourceGeometry();
    }

    private void refreshSourceGeometry() {
        if (mSession == null || mSession.closed || mSession.change != null || mGeometryRefreshPending) return;
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
        final DisplayPresentations.Session session = mSession;
        DisplayOperations.readDisplays((displays, error) -> runOnUiThread(() -> {
            mGeometryRefreshPending = false;
            if (isDestroyed() || session != mSession || session.closed) return;
            if (error != null) {
                DisplayPresentations.failed(mSession, new IllegalStateException(error));
                return;
            }
            for (DesktopDisplayInfo source : displays) {
                if (source.id == mSession.source.id) {
                    if (!source.uniqueId.equals(mSession.source.uniqueId)) DisplayPresentations.detach(mSession);
                    else DisplayPresentations.updateGeometry(mSession, source);
                    return;
                }
            }
            DisplayPresentations.detach(mSession);
        }));
    }
    @Override public void onDisplayRemoved(int displayId) {
        if (mSession != null && (displayId == mSession.source.id || displayId == mSession.output.id)) {
            DisplayPresentations.detach(mSession);
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
        mStarted = true;
        if (mSession != null) DisplayPresentations.visibilityChanged(mSession, true);
    }

    @Override protected void onStop() {
        mDisplayKeys.reset();
        DisplaySwitchController.cancelFor(this);
        mStarted = false;
        if (mSession != null) DisplayPresentations.visibilityChanged(mSession, false);
        super.onStop();
    }

    private void verifyOutput() {
        if (mSession != null && (getDisplay() == null
                || getDisplay().getDisplayId() != mSession.output.id)) {
            final DesktopDisplayInfo source = mSession.source;
            final boolean attachment = mSession.outputAttachment;
            DisplayPresentations.detach(mSession);
            if (!attachment && getDisplay() != null) bindSource(source);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (mSession != null) state.putString(SESSION, mSession.id);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        DisplaySwitchController.cancelFor(this);
        mSelectionGeneration++;
        if (mSourceDialog != null) mSourceDialog.dismiss();
        mConnection.close();
        if (mDisplays != null) mDisplays.unregisterDisplayListener(this);
        if (mSession != null && mSession.listener == this) {
            mSession.listener = null;
            mSession.taskId = -1;
            if (!isChangingConfigurations()) DisplayPresentations.detach(mSession);
        }
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }
}
