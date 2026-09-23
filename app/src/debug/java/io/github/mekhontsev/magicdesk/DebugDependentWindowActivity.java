package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.PopupWindow;

/** Public-API fixture for paint and input outside a freeform parent task. */
public final class DebugDependentWindowActivity extends Activity {
    private PopupWindow popup;
    private HostedDependentWindow embedded;
    private IInputRegionReceipt inputReceipt;
    private HostedShellSurfaceView rendered;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.CYAN);
        SurfaceView anchor = new SurfaceView(this);
        anchor.setZOrderOnTop(true);
        anchor.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        anchor.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            @Override public void surfaceCreated(android.view.SurfaceHolder holder) {
                var canvas = holder.lockCanvas();
                if (canvas != null) {
                    canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
                    holder.unlockCanvasAndPost(canvas);
                }
            }
            @Override public void surfaceChanged(android.view.SurfaceHolder holder, int format, int w, int h) { }
            @Override public void surfaceDestroyed(android.view.SurfaceHolder holder) { }
        });
        root.addView(anchor, new FrameLayout.LayoutParams(1, 1));
        Button show = new Button(this);
        show.setText("Show dependent window");
        root.addView(show, new FrameLayout.LayoutParams(300, 80, Gravity.CENTER));
        show.setOnClickListener(view -> {
            if (popup != null) popup.dismiss();
            releaseEmbedded();
            if (getIntent().getBooleanExtra("rendered", false)) {
                try {
                    var output = new FixtureOutput();
                    rendered = new HostedShellSurfaceView(this, output);
                    rendered.keyboard(true);
                    int x = root.getWidth() - 80;
                    embedded = new HostedDependentWindow(this, anchor, rendered, new ShellBounds(x, 100, x + 360, 340));
                    rendered.screenOrigin(embedded::locateOnScreen);
                    embedded.placementChanged(rendered::placementChanged);
                    embedded.ready().whenComplete((unused, error) -> {
                        if (error != null) { Log.e("DependentWindowFixture", "render host failed", error); return; }
                        rendered.present(new HostedShellFrame(new ShellBounds(0, 0, 360, 240), true,
                                java.util.List.of(new ShellBounds(0, 0, 360, 100), new ShellBounds(0, 140, 360, 240))))
                                .whenComplete((ignored, failure) -> {
                                    if (failure != null) Log.e("DependentWindowFixture", "render input failed", failure);
                                    else Log.i("DependentWindowFixture", "render input ready");
                                });
                    });
                    embedded.ended().whenComplete((unused, error) -> Log.i("DependentWindowFixture", "render host ended"));
                } catch (RuntimeException error) {
                    Log.e("DependentWindowFixture", "render host failed", error);
                    releaseEmbedded();
                }
                return;
            }
            Button content = new Button(this);
            content.setText("Outside clicks: 0");
            content.setBackgroundColor(Color.MAGENTA);
            int[] clicks = {0};
            content.setOnClickListener(button -> {
                content.setText("Outside clicks: " + ++clicks[0]);
                Log.i("DependentWindowFixture", "click=" + clicks[0]);
            });
            if (getIntent().getBooleanExtra("embedded", false)) {
                try {
                    FrameLayout panel = new FrameLayout(this);
                    panel.setBackgroundColor(Color.MAGENTA);
                    panel.addView(content, new FrameLayout.LayoutParams(360, 100));
                    android.widget.EditText text = new android.widget.EditText(this);
                    text.setSingleLine(true);
                    text.setHint("Dependent input");
                    var textParams = new FrameLayout.LayoutParams(360, 100);
                    textParams.topMargin = 110;
                    panel.addView(text, textParams);
                    int x = root.getWidth() - 80;
                    embedded = new HostedDependentWindow(this, anchor, panel,
                            new ShellBounds(x, 100, x + 360, 340));
                    embedded.ready().whenComplete((unused, error) -> {
                        if (error == null) {
                            Log.i("DependentWindowFixture", "embedded attached");
                            int[] location = new int[2];
                            panel.getLocationOnScreen(location);
                            Log.i("DependentWindowFixture", "embedded view location=" + location[0] + "," + location[1]);
                            if (getIntent().getBooleanExtra("sparse", false)) {
                                var region = new android.graphics.Region(0, 0, 360, 100);
                                region.op(0, 110, 360, 210, android.graphics.Region.Op.UNION);
                                panel.getRootSurfaceControl().setTouchableRegion(region);
                                anchor.getLocationOnScreen(location);
                                region.translate(location[0] + x, location[1] + 100);
                                try {
                                    inputReceipt = ShellAccess.observeWindowInputRegion(panel.getWindowToken(),
                                            getDisplay().getDisplayId(), region, new IInputRegionCallback.Stub() {
                                        @Override public void completed(String failure) {
                                            Log.i("DependentWindowFixture", "sparse input receipt=" + failure);
                                        }
                                    });
                                } catch (java.io.IOException failure) {
                                    Log.e("DependentWindowFixture", "sparse input observation failed", failure);
                                }
                            }
                        }
                        else Log.e("DependentWindowFixture", "embedded failed", error);
                    });
                    embedded.ended().whenComplete((unused, error) -> Log.i("DependentWindowFixture", "embedded ended"));
                } catch (Exception error) {
                    Log.e("DependentWindowFixture", "embedded failed", error);
                    releaseEmbedded();
                }
                return;
            }
            popup = new PopupWindow(content, 360, 240, false);
            popup.setBackgroundDrawable(new ColorDrawable(Color.MAGENTA));
            popup.setTouchModal(false);
            popup.setIsLaidOutInScreen(true);
            popup.setIsClippedToScreen(true);
            popup.setClippingEnabled(!getIntent().getBooleanExtra("noLimits", false));
            int x = root.getWidth() - 80;
            int y = 100;
            popup.showAtLocation(root, Gravity.TOP | Gravity.LEFT, x, y);
            content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int[] actual = new int[2];
                content.getLocationOnScreen(actual);
                Log.i("DependentWindowFixture", "requested=" + x + "," + y
                        + " actual=" + actual[0] + "," + actual[1]
                        + " size=" + content.getWidth() + "x" + content.getHeight());
            });
        });
        setContentView(root);
    }

    @Override protected void onDestroy() {
        if (popup != null) popup.dismiss();
        releaseEmbedded();
        super.onDestroy();
    }

    private void releaseEmbedded() {
        if (inputReceipt != null) {
            try { inputReceipt.cancel(); }
            catch (android.os.RemoteException ignored) { }
            inputReceipt = null;
        }
        if (embedded != null) embedded.close();
        embedded = null;
        if (rendered != null) rendered.close();
        rendered = null;
    }

    private static final class FixtureOutput implements HostedShellOutput {
        @Override public java.util.concurrent.CompletableFuture<Void> present(android.view.Surface surface, ShellBounds viewport) {
            var canvas = surface.lockCanvas(null);
            try {
                canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
                var paint = new android.graphics.Paint();
                paint.setColor(Color.MAGENTA);
                canvas.drawRect(0, 0, 360, 100, paint);
                paint.setColor(Color.YELLOW);
                canvas.drawRect(0, 140, 360, 240, paint);
            } finally { surface.unlockCanvasAndPost(canvas); }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        @Override public void setSurface(android.view.Surface surface, int width, int height) { }
        @Override public void focus() { Log.i("DependentWindowFixture", "render focus"); }
        @Override public void pointer(float x, float y) { }
        @Override public void button(float x, float y, Button button, boolean down) {
            Log.i("DependentWindowFixture", "render button=" + button + " down=" + down + " x=" + x + " y=" + y);
        }
        @Override public void scroll(float x, float y, float horizontal, float vertical) { }
        @Override public void key(int key, int scan, boolean down) { }
        @Override public void text(String text) { }
        @Override public boolean supportsText() { return false; }
        @Override public void close() { Log.i("DependentWindowFixture", "render output closed"); }
    }
}
