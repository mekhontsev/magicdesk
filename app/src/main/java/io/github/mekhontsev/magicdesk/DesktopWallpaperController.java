package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

final class DesktopWallpaperController {
    private static final String TAG = "MagicDeskWallpaper";
    private static final long MAX_DECODED_PIXELS = 16L * 1024 * 1024;

    private final DesktopShellActivity mActivity;
    private final Context mContext;
    private final ImageView mWallpaperView;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger mLoadGeneration = new AtomicInteger();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable runnable) {
                    return new Thread(runnable, "MagicDeskWallpaper");
                }
            });

    private boolean mStarted;
    private volatile boolean mUsingCustomWallpaper;
    private volatile boolean mUsingFallbackWallpaper;
    private volatile boolean mRendered;

    DesktopWallpaperController(
            final DesktopShellActivity activity,
            final ImageView wallpaperView) {
        mActivity = activity;
        mContext = activity.getApplicationContext();
        mWallpaperView = wallpaperView;
    }

    void start() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        reload();
    }

    void stop() {
        if (!mStarted) {
            return;
        }
        mStarted = false;
        mRendered = false;
        mLoadGeneration.incrementAndGet();
        mExecutor.shutdownNow();
    }

    void useDefaultWallpaper() {
        if (!mStarted) {
            return;
        }
        mExecutor.execute(() -> {
            try {
                ShellAccess.deleteDesktopWallpaper();
                postToActivity(() -> {
                    mActivity.setStatus(mActivity.getString(
                            R.string.status_default_wallpaper_restored));
                    reload();
                });
            } catch (IOException | RuntimeException error) {
                postToActivity(() -> mActivity.setErrorStatus(
                        "WALLPAPER-003",
                        mActivity.getString(
                                R.string.status_desktop_wallpaper_failed,
                                usefulMessage(error)),
                        ShellDesktopDirectory.WALLPAPER_RELATIVE_PATH,
                        error));
            }
        });
    }

    private void postToActivity(final Runnable action) {
        mMainHandler.post(() -> {
            if (mStarted && !mActivity.isActivityUnavailable()) {
                action.run();
            }
        });
    }

    boolean isUsingCustomWallpaper() {
        return mUsingCustomWallpaper;
    }

    boolean isRendered() {
        return mRendered;
    }

    boolean isUsingFallbackWallpaper() {
        return mUsingFallbackWallpaper;
    }

    void reloadExternal() {
        reload();
    }

    private void reload() {
        if (!mStarted) {
            return;
        }
        mRendered = false;
        final int generation = mLoadGeneration.incrementAndGet();
        final DisplayMetrics metrics = mWallpaperView.getResources().getDisplayMetrics();
        final int targetWidth = Math.max(1, metrics.widthPixels);
        final int targetHeight = Math.max(1, metrics.heightPixels);
        final BooleanSupplier cancelled = () -> generation != mLoadGeneration.get();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ContentStreamCopy.checkCancelled(cancelled);
                    final WallpaperResult source = loadWallpaper(
                            targetWidth, targetHeight, cancelled);
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        source.bitmap.recycle();
                        return;
                    }
                    final WallpaperResult result = renderDisplayFrame(
                            source,
                            targetWidth,
                            targetHeight);
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!mStarted || generation != mLoadGeneration.get()) {
                                result.bitmap.recycle();
                                return;
                            }
                            mUsingCustomWallpaper = result.custom;
                            mUsingFallbackWallpaper = result.fallback;
                            mWallpaperView.setImageBitmap(result.bitmap);
                            publishRenderedFrame(generation, result);
                        }
                    });
                } catch (IOException | RuntimeException error) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    Log.w(TAG, "Cannot render desktop background", error);
                    CompatibilityDiagnostics.record(
                            "WALLPAPER-002",
                            "Could not render the desktop background",
                            usefulMessage(error),
                            error);
                }
            }
        });
    }

    private void publishRenderedFrame(
            final int generation,
            final WallpaperResult result) {
        mWallpaperView.getViewTreeObserver().registerFrameCommitCallback(() ->
                mWallpaperView.post(() -> {
                    if (!mStarted
                            || generation != mLoadGeneration.get()
                            || mActivity.isActivityUnavailable()) {
                        return;
                    }
                    mRendered = true;
                    recordRenderedEvent(result);
                }));
        mWallpaperView.invalidate();
    }

    private void recordRenderedEvent(final WallpaperResult result) {
        final Drawable drawable = mWallpaperView.getDrawable();
        try {
            DesktopAutomationEventJournal.record(
                    "ui",
                    "wallpaper_rendered",
                    true,
                    "display=" + mActivity.getCurrentDisplayId(),
                    new org.json.JSONObject()
                            .put("displayId", mActivity.getCurrentDisplayId())
                            .put("custom", result.custom)
                            .put("fallback", result.fallback)
                            .put("source", result.fallback ? "fallback"
                                    : result.custom ? "custom" : "bundled")
                            .put("bitmapWidth", result.bitmap.getWidth())
                            .put("bitmapHeight", result.bitmap.getHeight())
                            .put("bitmapDensity", result.bitmap.getDensity())
                            .put("drawableWidth", drawable != null
                                    ? drawable.getIntrinsicWidth() : -1)
                            .put("drawableHeight", drawable != null
                                    ? drawable.getIntrinsicHeight() : -1)
                            .put("viewWidth", mWallpaperView.getWidth())
                            .put("viewHeight", mWallpaperView.getHeight())
                            .put("displayDensity", mWallpaperView.getResources()
                                    .getDisplayMetrics().densityDpi));
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "ui", "wallpaper_rendered", true,
                    "display=" + mActivity.getCurrentDisplayId());
        }
    }

    private WallpaperResult loadWallpaper(
            final int targetWidth,
            final int targetHeight,
            final BooleanSupplier cancelled) throws IOException {
        final File customCacheFile = new File(
                mContext.getCacheDir(), "desktop-custom-wallpaper");
        if (!ShellAccess.isReady()) {
            return cachedOrDefault(customCacheFile, targetWidth, targetHeight);
        }
        final File pendingFile;
        try {
            pendingFile = createPendingFile(mContext.getCacheDir());
        } catch (IOException | RuntimeException error) {
            ContentStreamCopy.checkCancelled(cancelled);
            Log.w(TAG, "Cannot prepare wallpaper cache", error);
            return cachedOrDefault(customCacheFile, targetWidth, targetHeight);
        }
        try {
            if (copyCustomWallpaper(pendingFile, cancelled)) {
                return decodeAndCache(pendingFile, customCacheFile,
                        targetWidth, targetHeight, cancelled);
            }
            ContentStreamCopy.checkCancelled(cancelled);
            customCacheFile.delete();
        } catch (IOException | RuntimeException error) {
            ContentStreamCopy.checkCancelled(cancelled);
            Log.w(TAG, "Custom desktop wallpaper unavailable", error);
            CompatibilityDiagnostics.record(
                    "WALLPAPER-003", "Custom desktop wallpaper unavailable",
                    usefulMessage(error), error);
            return cachedOrDefault(customCacheFile, targetWidth, targetHeight);
        } finally {
            pendingFile.delete();
        }
        return defaultWallpaper(targetWidth, targetHeight);
    }

    private WallpaperResult cachedOrDefault(
            final File cacheFile,
            final int targetWidth, final int targetHeight) {
        if (cacheFile.isFile()) {
            try {
                return new WallpaperResult(decodeWallpaper(
                        cacheFile, targetWidth, targetHeight), true, false);
            } catch (IOException error) {
                Log.w(TAG, "Ignoring invalid cached wallpaper", error);
                cacheFile.delete();
            }
        }
        return defaultWallpaper(targetWidth, targetHeight);
    }

    private WallpaperResult decodeAndCache(
            final File pendingFile, final File cacheFile,
            final int targetWidth, final int targetHeight,
            final BooleanSupplier cancelled) throws IOException {
        ContentStreamCopy.checkCancelled(cancelled);
        final Bitmap wallpaper = decodeWallpaper(pendingFile, targetWidth, targetHeight);
        try {
            ContentStreamCopy.checkCancelled(cancelled);
            replaceCachedWallpaper(pendingFile, cacheFile);
            return new WallpaperResult(wallpaper, true, false);
        } catch (IOException | RuntimeException | Error error) {
            wallpaper.recycle();
            throw error;
        }
    }

    static File createPendingFile(final File directory) throws IOException {
        // Controllers may overlap while their HOME hosts are being replaced.
        return File.createTempFile("desktop-wallpaper-", ".pending", directory);
    }

    private static WallpaperResult renderDisplayFrame(
            final WallpaperResult source,
            final int targetWidth,
            final int targetHeight) {
        final Bitmap sourceBitmap = source.bitmap;
        // The final frame uses display pixels, not density-scaled drawable units.
        if (sourceBitmap.getWidth() == targetWidth
                && sourceBitmap.getHeight() == targetHeight) {
            sourceBitmap.setDensity(Bitmap.DENSITY_NONE);
            return source;
        }
        final Bitmap frame = Bitmap.createBitmap(
                targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        try {
            frame.setDensity(Bitmap.DENSITY_NONE);
            final float scale = Math.max(
                    targetWidth / (float) sourceBitmap.getWidth(),
                    targetHeight / (float) sourceBitmap.getHeight());
            final float width = sourceBitmap.getWidth() * scale;
            final float height = sourceBitmap.getHeight() * scale;
            final RectF destination = new RectF(
                    (targetWidth - width) / 2.0f,
                    (targetHeight - height) / 2.0f,
                    (targetWidth + width) / 2.0f,
                    (targetHeight + height) / 2.0f);
            final Paint paint = new Paint(
                    Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            new Canvas(frame).drawBitmap(
                    sourceBitmap, null, destination, paint);
            sourceBitmap.recycle();
            return new WallpaperResult(
                    frame, source.custom, source.fallback);
        } catch (RuntimeException error) {
            frame.recycle();
            sourceBitmap.recycle();
            throw error;
        }
    }

    private WallpaperResult defaultWallpaper(
            final int targetWidth,
            final int targetHeight) {
        try {
            return new WallpaperResult(
                    loadBundledWallpaper(targetWidth, targetHeight),
                    false,
                    false);
        } catch (RuntimeException error) {
            Log.w(TAG, "Bundled wallpaper unavailable", error);
            CompatibilityDiagnostics.record(
                    "WALLPAPER-001",
                    "Bundled wallpaper unavailable; using desktop fallback",
                    usefulMessage(error),
                    error);
        }
        return new WallpaperResult(
                createFallbackWallpaper(targetWidth, targetHeight),
                false,
                true);
    }

    private Bitmap loadBundledWallpaper(
            final int targetWidth,
            final int targetHeight) {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(mContext.getResources(), R.drawable.desktop_wallpaper, bounds);
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inSampleSize = calculateSampleSize(
                bounds.outWidth, bounds.outHeight, targetWidth, targetHeight);
        final Bitmap wallpaper = BitmapFactory.decodeResource(
                mContext.getResources(), R.drawable.desktop_wallpaper, options);
        if (wallpaper == null) {
            throw new IllegalStateException("bundled wallpaper decode failed");
        }
        return wallpaper;
    }

    private Bitmap decodeWallpaper(
            final File cacheFile,
            final int targetWidth,
            final int targetHeight) throws IOException {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(cacheFile.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("custom wallpaper is not a decodable image");
        }

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateSampleSize(
                bounds.outWidth, bounds.outHeight, targetWidth, targetHeight);
        final Bitmap wallpaper = BitmapFactory.decodeFile(
                cacheFile.getAbsolutePath(), options);
        if (wallpaper == null) {
            throw new IOException("custom wallpaper decode failed");
        }
        return wallpaper;
    }

    private static Bitmap createFallbackWallpaper(
            final int targetWidth, final int targetHeight) {
        final Bitmap wallpaper = Bitmap.createBitmap(
                targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        wallpaper.eraseColor(0xFF090D14);
        return wallpaper;
    }

    private static boolean copyCustomWallpaper(
            final File destination, final BooleanSupplier cancelled)
            throws IOException {
        ContentStreamCopy.checkCancelled(cancelled);
        final ParcelFileDescriptor descriptor =
                ShellAccess.openDesktopWallpaper();
        if (descriptor == null) {
            return false;
        }
        try (InputStream input =
                        new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                FileOutputStream output =
                        new FileOutputStream(destination, false)) {
            if (ContentStreamCopy.copy(input, output, cancelled,
                    ShellDesktopDirectory.MAX_WALLPAPER_BYTES) == 0) {
                throw new IOException("custom desktop wallpaper is empty");
            }
        }
        return true;
    }

    private static void replaceCachedWallpaper(
            final File source,
            final File destination) {
        try {
            Os.rename(source.getAbsolutePath(), destination.getAbsolutePath());
        } catch (ErrnoException error) {
            Log.w(TAG, "Cannot update cached wallpaper", error);
        }
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim();
    }

    static int calculateSampleSize(final int sourceWidth, final int sourceHeight,
            final int targetWidth, final int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0
                || targetWidth <= 0 || targetHeight <= 0) {
            throw new IllegalArgumentException("wallpaper dimensions must be positive");
        }
        int sampleSize = 1;
        while (sourceWidth / (sampleSize * 2L) >= targetWidth
                && sourceHeight / (sampleSize * 2L) >= targetHeight) {
            sampleSize *= 2;
        }
        // Encoded size and the short image edge do not bound decoded memory.
        while (((sourceWidth + sampleSize - 1L) / sampleSize)
                * ((sourceHeight + sampleSize - 1L) / sampleSize)
                > MAX_DECODED_PIXELS) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private static final class WallpaperResult {
        final Bitmap bitmap;
        final boolean custom;
        final boolean fallback;

        WallpaperResult(
                final Bitmap bitmap,
                final boolean custom,
                final boolean fallback) {
            this.bitmap = bitmap;
            this.custom = custom;
            this.fallback = fallback;
        }
    }

}
