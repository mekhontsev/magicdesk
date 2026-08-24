package io.github.mekhontsev.magicdesk;

import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Browser-shaped fixture for application-requested fullscreen transitions. */
public final class DesktopSelfTestBrowserActivity
        extends DesktopSelfTestActivity {
    private static final int TOOLBAR_HEIGHT = 96;
    private FrameLayout mBrowserContainer;
    private SurfaceView mBrowserSurface;
    private View mToolbar;

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
        window.setAttributes(attributes);

        mToolbar.setVisibility(enabled ? View.GONE : View.VISIBLE);
        final FrameLayout.LayoutParams browserLayout =
                (FrameLayout.LayoutParams) mBrowserContainer.getLayoutParams();
        browserLayout.topMargin = enabled ? 0 : TOOLBAR_HEIGHT;
        mBrowserContainer.setLayoutParams(browserLayout);
        mBrowserContainer.requestLayout();
        mBrowserSurface.requestLayout();
    }

    private void drawSurface(final SurfaceHolder holder) {
        final Canvas canvas = holder.lockCanvas();
        if (canvas == null) {
            return;
        }
        try {
            canvas.drawColor(fixtureSurfaceColor());
        } finally {
            holder.unlockCanvasAndPost(canvas);
        }
    }
}
