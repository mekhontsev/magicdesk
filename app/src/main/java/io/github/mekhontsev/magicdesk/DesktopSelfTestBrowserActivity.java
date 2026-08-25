package io.github.mekhontsev.magicdesk;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

/** Browser-shaped fixture for application-requested fullscreen transitions. */
public final class DesktopSelfTestBrowserActivity
        extends DesktopSelfTestActivity {
    private static final int TOOLBAR_HEIGHT = 96;
    private static final int SURFACE_MARKER_COLOR = Color.WHITE;
    private FrameLayout mBrowserContainer;
    private SurfaceView mBrowserSurface;
    private View mToolbar;
    private boolean mFullscreenSurfaceMarkerPending;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        final Window window = getWindow();
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams
                                .FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_SPLIT_TOUCH);
        window.setDecorFitsSystemWindows(false);
        window.getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected FrameLayout createContent() {
        final FrameLayout content = super.createContent();
        content.setBackgroundColor(Color.TRANSPARENT);
        mBrowserContainer = new FrameLayout(this);
        mBrowserSurface = new SurfaceView(this);
        mBrowserSurface.addOnLayoutChangeListener((view, left, top, right,
                bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (mFullscreenSurfaceMarkerPending) {
                drawSurface(mBrowserSurface.getHolder());
            }
        });
        mBrowserSurface.getHolder().setFormat(PixelFormat.OPAQUE);
        mBrowserSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(final SurfaceHolder holder) {
                drawSurface(holder);
            }

            @Override
            public void surfaceChanged(
                    final SurfaceHolder holder,
                    final int format,
                    final int width,
                    final int height) {
                drawSurface(holder);
            }

            @Override
            public void surfaceDestroyed(final SurfaceHolder holder) {
            }
        });
        mBrowserContainer.addView(mBrowserSurface,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        final FrameLayout.LayoutParams browserLayout =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
        browserLayout.topMargin = TOOLBAR_HEIGHT;
        content.addView(mBrowserContainer, 0, browserLayout);

        mToolbar = new View(this);
        mToolbar.setBackgroundColor(0xFF29343B);
        content.addView(mToolbar, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                TOOLBAR_HEIGHT));
        return content;
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (isImmersiveEnabled()) {
            // Firefox exposes system bars while its video task is in the
            // background, then requests immersive bars again on resume.
            applyImmersiveBars(hasFocus);
        }
    }

    @Override
    public void onMultiWindowModeChanged(
            final boolean isInMultiWindowMode,
            final Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        recordWindowModeTransition(isInMultiWindowMode);
        if (!isInMultiWindowMode && mFullscreenSurfaceMarkerPending) {
            drawSurface(mBrowserSurface.getHolder());
        }
    }

    @Override
    public void onConfigurationChanged(final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (isImmersiveEnabled() && hasWindowFocus()) {
            // Browser video players renew their immersive request after the
            // fixed-orientation configuration reaches the activity.
            applyImmersiveBars(true);
        }
    }

    @Override
    protected View immersiveInsetsView() {
        return mBrowserSurface == null
                ? super.immersiveInsetsView() : mBrowserSurface;
    }

    @Override
    protected void configureImmersiveWindow(final boolean enabled) {
        // Gecko video fullscreen changes the activity orientation together
        // with its insets and window flags. Keep this fixture sequence intact:
        // the extra configuration transaction is part of the restore path.
        setRequestedOrientation(enabled
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_USER);
        final Window window = getWindow();
        final WindowManager.LayoutParams attributes = window.getAttributes();
        if (enabled) {
            mBrowserSurface.setFocusableInTouchMode(true);
            mBrowserSurface.requestFocus();
            final InputMethodManager inputMethod = getSystemService(
                    InputMethodManager.class);
            if (inputMethod != null) {
                inputMethod.hideSoftInputFromWindow(
                        mBrowserSurface.getWindowToken(), 0);
            }
            window.setFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        }
        if (!enabled) {
            mBrowserSurface.clearFocus();
        }
        window.setAttributes(attributes);
        window.setBackgroundDrawable(new ColorDrawable(enabled
                ? fixtureSurfaceColor() : Color.TRANSPARENT));

        mToolbar.setVisibility(enabled ? View.GONE : View.VISIBLE);
        final FrameLayout.LayoutParams browserLayout =
                (FrameLayout.LayoutParams) mBrowserContainer.getLayoutParams();
        browserLayout.topMargin = enabled ? 0 : TOOLBAR_HEIGHT;
        mBrowserContainer.setLayoutParams(browserLayout);
        mBrowserContainer.requestLayout();
        mBrowserSurface.requestLayout();
    }

    @Override
    protected void recordImmersiveFrame(final boolean enabled) {
        if (!enabled) {
            mFullscreenSurfaceMarkerPending = false;
            super.recordImmersiveFrame(false);
            return;
        }
        mFullscreenSurfaceMarkerPending = true;
        recordFullscreenSurfaceFrameIfReady(mBrowserSurface.getHolder());
    }

    private void drawSurface(final SurfaceHolder holder) {
        final Canvas canvas = holder.lockCanvas();
        if (canvas == null) {
            return;
        }
        try {
            canvas.drawColor(fixtureSurfaceColor());
            final Paint marker = new Paint();
            marker.setColor(SURFACE_MARKER_COLOR);
            canvas.drawRect(
                    canvas.getWidth() * 3 / 4f,
                    canvas.getHeight() / 3f,
                    canvas.getWidth(),
                    canvas.getHeight() * 2 / 3f,
                    marker);
        } finally {
            holder.unlockCanvasAndPost(canvas);
        }
        recordFullscreenSurfaceFrameIfReady(holder);
    }

    private void recordFullscreenSurfaceFrameIfReady(
            final SurfaceHolder holder) {
        if (!mFullscreenSurfaceMarkerPending || holder == null) {
            return;
        }
        final android.graphics.Rect surfaceFrame = holder.getSurfaceFrame();
        final int viewWidth = mBrowserSurface.getWidth();
        final int viewHeight = mBrowserSurface.getHeight();
        final Point displaySize = new Point();
        if (getDisplay() != null) {
            getDisplay().getRealSize(displaySize);
        }
        // Full display width distinguishes the post-transition fullscreen
        // layout from the preceding freeform surface. A desktop session keeps
        // its viewport orientation even when this activity requests landscape.
        // Height can legitimately retain a transient system inset; the
        // following pixel check verifies that edge independently.
        if (viewWidth <= 0
                || viewHeight <= 0
                || displaySize.x <= 0
                || displaySize.y <= 0
                || isInMultiWindowMode()
                || viewWidth < displaySize.x
                || surfaceFrame.width() < viewWidth
                || surfaceFrame.height() < viewHeight) {
            return;
        }
        final int[] location = new int[2];
        mBrowserSurface.getLocationOnScreen(location);
        recordImmersiveSurfaceBounds(new android.graphics.Rect(
                location[0],
                location[1],
                location[0] + viewWidth,
                location[1] + viewHeight));
        mFullscreenSurfaceMarkerPending = false;
        super.recordImmersiveFrame(true);
    }
}
