package io.github.mekhontsev.magicdesk;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Matrix;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Per-output playback host. No input, focus or window transactions. */
final class WallpaperView extends FrameLayout {
    private interface Playback { void close(); }

    private final ImageView image;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor decoder;
    private final DisplayManager displays;
    private final PowerManager power;
    private WallpaperAsset asset;
    private Playback playback;
    private Consumer<Throwable> failure = error -> { };
    private Consumer<String> state = value -> { };
    private int generation, playbackGeneration;
    private boolean attached, preparing, failed, closed;
    private int outputWidth, outputHeight;
    private ShellResources.WallpaperFit fit = ShellResources.WallpaperFit.COVER;
    private final Runnable appearanceChanged = () -> { refresh(); applyFit(); };
    private final ContentObserver animations = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };
    private final BroadcastReceiver powerChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };
    private final DisplayManager.DisplayListener displayChanged = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { refresh(); }
        @Override public void onDisplayRemoved(int id) { refresh(); }
        @Override public void onDisplayChanged(int id) { refresh(); }
    };

    WallpaperView(Context context, Executor decoder) {
        super(context);
        this.decoder = decoder;
        displays = context.getSystemService(DisplayManager.class);
        power = context.getSystemService(PowerManager.class);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.MATRIX);
        addView(image);
    }

    void show(WallpaperAsset value, int width, int height, Runnable rendered,
            Consumer<String> state, Consumer<Throwable> failure) {
        endPlayback();
        asset = value; outputWidth = width; outputHeight = height;
        fit = AppearanceStore.current(getContext()).resources().fit();
        this.state = state; this.failure = failure; failed = false;
        int token = ++generation;
        showPoster();
        getViewTreeObserver().registerFrameCommitCallback(() -> main.post(() -> {
            if (!closed && generation == token) rendered.run();
        }));
        invalidate();
        refresh();
    }

    private void applyFit() {
        ShellResources.WallpaperFit updated = AppearanceStore.current(getContext()).resources().fit();
        if (updated == fit) return;
        fit = updated;
        endPlayback();
        refresh();
    }

    private void setImage(Drawable drawable) {
        image.setImageDrawable(drawable);
        var crop = WallpaperPolicy.crop(fit, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), outputWidth, outputHeight);
        Matrix matrix = new Matrix(); matrix.setScale(crop.scale(), crop.scale()); matrix.postTranslate(crop.x(), crop.y());
        image.setImageMatrix(matrix);
        image.setLayoutParams(new FrameLayout.LayoutParams(outputWidth, outputHeight));
        image.setVisibility(VISIBLE);
    }

    private void showPoster() {
        if (asset != null) setImage(new BitmapDrawable(getResources(), asset.poster()));
    }

    private boolean shouldPlay() {
        Display display = getDisplay();
        var motion = AppearanceStore.current(getContext()).motion();
        return !closed && !failed && asset != null && asset.kind() != WallpaperAsset.Kind.IMAGE
                && (asset.kind() != WallpaperAsset.Kind.SHADER || isHardwareAccelerated())
                && WallpaperPolicy.animate(motion.wallpaper(), motion.reduced(), ValueAnimator.areAnimatorsEnabled(),
                        power != null && power.isPowerSaveMode(), attached && isShown() && getWindowVisibility() == VISIBLE,
                        display != null && display.getState() == Display.STATE_ON);
    }

    private void refresh() {
        if (image == null) return;
        if (!shouldPlay()) { endPlayback(); return; }
        if (preparing || playback != null) return;
        preparing = true;
        int token = ++playbackGeneration;
        WallpaperAsset selected = asset;
        if (selected.kind() == WallpaperAsset.Kind.VIDEO) {
            try {
                VideoPlayback video = new VideoPlayback(token, selected);
                playback = video;
                video.attach();
            }
            catch (RuntimeException error) { failed(token, error); }
            return;
        }
        int width = outputWidth, height = outputHeight;
        if (selected.kind() == WallpaperAsset.Kind.SHADER) {
            prepareShader(token, selected, width, height);
            return;
        }
        decoder.execute(() -> {
            try {
                AnimatedImageDrawable animation = selected.animation(width, height);
                main.post(() -> {
                    if (token != playbackGeneration || !shouldPlay()) { animation.stop(); animation.setCallback(null); return; }
                    preparing = false;
                    playback = () -> { animation.stop(); animation.clearAnimationCallbacks(); animation.setCallback(null); };
                    setImage(animation);
                    animation.start();
                    state.accept("playing");
                });
            } catch (Exception error) { main.post(() -> failed(token, error)); }
        });
    }

    private void prepareShader(int token, WallpaperAsset selected, int width, int height) {
        decoder.execute(() -> {
            try {
                var shader = selected.shader().createShader(width, height);
                main.post(() -> {
                    if (token != playbackGeneration || !shouldPlay()) return;
                    ShaderWallpaperDrawable drawable = new ShaderWallpaperDrawable(shader, width, height,
                            selected.shader().spec.fps(), selected.shader().spec.fallbackColor(),
                            selected.shader().spec.signals().isEmpty() ? null : new ShaderSignalBindings(
                                    selected.shader().spec.signals(), requested -> AppearanceSignalSources.acquire(getContext(), requested)),
                            error -> main.post(() -> failed(token, error)));
                    preparing = false;
                    playback = drawable::close;
                    setImage(drawable);
                    drawable.start();
                    image.getViewTreeObserver().registerFrameCommitCallback(() -> main.post(() -> {
                        if (token == playbackGeneration && shouldPlay()) state.accept("playing");
                    }));
                });
            } catch (Exception error) { main.post(() -> failed(token, error)); }
        });
    }

    private void failed(int token, Throwable error) {
        if (token != playbackGeneration || closed) return;
        failed = true;
        endPlayback();
        state.accept("fallback");
        failure.accept(error);
    }

    private void endPlayback() {
        if (playback == null && !preparing) return;
        ++playbackGeneration;
        preparing = false;
        Playback previous = playback; playback = null;
        if (previous != null) previous.close();
        showPoster();
        state.accept("paused");
    }

    void close() {
        closed = true; ++generation;
        endPlayback();
        asset = null; image.setImageDrawable(null);
        state = value -> { }; failure = error -> { };
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); attached = true;
        AppearanceStore.listen(appearanceChanged);
        getContext().getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animations);
        getContext().registerReceiver(powerChanged, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        displays.registerDisplayListener(displayChanged, main);
        refresh();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false; endPlayback();
        AppearanceStore.unlisten(appearanceChanged);
        getContext().getContentResolver().unregisterContentObserver(animations);
        getContext().unregisterReceiver(powerChanged);
        displays.unregisterDisplayListener(displayChanged);
        super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); refresh(); }
    @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); refresh(); }

    private final class VideoPlayback implements Playback, SurfaceHolder.Callback {
        final int token;
        final WallpaperAsset selected;
        final SurfaceView surface;
        MediaPlayer player;
        Runnable timeout;
        boolean released;

        VideoPlayback(int token, WallpaperAsset selected) {
            this.token = token; this.selected = selected;
            surface = new SurfaceView(getContext());
            surface.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
        }
        void attach() {
            surface.getHolder().addCallback(this);
            addView(surface, 0, new FrameLayout.LayoutParams(outputWidth, outputHeight));
        }
        @Override public void surfaceCreated(SurfaceHolder holder) {
            if (released || token != playbackGeneration) return;
            try {
                player = new MediaPlayer();
                player.setVolume(0, 0);
                player.setDisplay(holder);
                player.setDataSource(selected.openVideo());
                player.setOnPreparedListener(ready -> {
                    if (released || player != ready) return;
                    try {
                        ready.setLooping(true);
                        ready.setVideoScalingMode(fit == ShellResources.WallpaperFit.CONTAIN
                                ? MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT
                                : MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                        ready.start();
                    } catch (RuntimeException error) { failed(token, error); }
                });
                player.setOnInfoListener((source, what, extra) -> {
                    if (!released && source == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        preparing = false; main.removeCallbacks(timeout);
                        image.setVisibility(INVISIBLE);
                        state.accept("playing");
                    }
                    return false;
                });
                player.setOnErrorListener((source, what, extra) -> {
                    if (!released && source == player) {
                        failed(token, new IllegalStateException("Wallpaper codec error " + what + "/" + extra));
                    }
                    return true;
                });
                // EVENT_WAIT: prepared and first-video-frame callbacks; expiry keeps the static poster.
                timeout = () -> failed(token, new IllegalStateException("Wallpaper video produced no frame"));
                main.postDelayed(timeout, 10_000);
                player.prepareAsync();
            } catch (Exception error) { failed(token, error); }
        }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
        @Override public void surfaceDestroyed(SurfaceHolder holder) { releasePlayer(); showPoster(); }
        private void releasePlayer() {
            if (timeout != null) main.removeCallbacks(timeout);
            if (player != null) {
                MediaPlayer previous = player; player = null;
                previous.setOnPreparedListener(null); previous.setOnInfoListener(null); previous.setOnErrorListener(null);
                previous.release();
            }
        }
        @Override public void close() {
            released = true; surface.getHolder().removeCallback(this); releasePlayer(); removeView(surface);
        }
    }
}
