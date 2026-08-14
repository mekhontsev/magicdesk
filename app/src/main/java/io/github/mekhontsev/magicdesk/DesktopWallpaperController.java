package io.github.mekhontsev.magicdesk;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
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

final class DesktopWallpaperController {
    private static final String TAG = "MagicDeskWallpaper";
    private static final int BUFFER_SIZE = 32 * 1024;

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
    private final BroadcastReceiver mWallpaperChangedReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context, final Intent intent) {
                    reload();
                }
            };

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
        mContext.registerReceiver(mWallpaperChangedReceiver,
                new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED));
        reload();
    }

    void stop() {
        if (!mStarted) {
            return;
        }
        mStarted = false;
        mRendered = false;
        mLoadGeneration.incrementAndGet();
        try {
            mContext.unregisterReceiver(mWallpaperChangedReceiver);
        } catch (IllegalArgumentException ignored) {
            // The process may already have detached the receiver.
        }
        mExecutor.shutdownNow();
    }

    void useSystemWallpaper() {
        mExecutor.execute(() -> {
            try {
                ShellAccess.deleteDesktopWallpaper();
                mMainHandler.post(() -> {
                    mActivity.setStatus(mActivity.getString(
                            R.string.status_system_wallpaper_restored));
                    reload();
                });
            } catch (IOException | RuntimeException error) {
                mMainHandler.post(() -> mActivity.setErrorStatus(
                        "WALLPAPER-003",
                        mActivity.getString(
                                R.string.status_desktop_wallpaper_failed,
                                usefulMessage(error)),
                        ShellDesktopDirectory.WALLPAPER_RELATIVE_PATH,
                        error));
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
        final int generation = mLoadGeneration.incrementAndGet();
        final DisplayMetrics metrics = mWallpaperView.getResources().getDisplayMetrics();
        final int targetWidth = Math.max(1, metrics.widthPixels);
        final int targetHeight = Math.max(1, metrics.heightPixels);
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final WallpaperResult result = loadWallpaper(
                            targetWidth, targetHeight);
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
                            mRendered = true;
                        }
                    });
                } catch (RuntimeException error) {
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

    private WallpaperResult loadWallpaper(
            final int targetWidth,
            final int targetHeight) {
        final File cacheFile = new File(
                mContext.getCacheDir(), "desktop-wallpaper");
        final File customCacheFile = new File(
                mContext.getCacheDir(), "desktop-custom-wallpaper");
        if (!ShellAccess.isReady()) {
            if (customCacheFile.isFile()) {
                try {
                    return new WallpaperResult(decodeWallpaper(
                            customCacheFile,
                            targetWidth,
                            targetHeight), true, false);
                } catch (IOException error) {
                    customCacheFile.delete();
                }
            }
            return cachedOrBuiltInOrFallback(
                    cacheFile, targetWidth, targetHeight);
        }
        final File pendingFile = new File(
                mContext.getCacheDir(), "desktop-wallpaper.pending");
        try {
            if (copyCustomWallpaper(pendingFile)) {
                final Bitmap wallpaper = decodeWallpaper(
                        pendingFile, targetWidth, targetHeight);
                replaceCachedWallpaper(pendingFile, customCacheFile);
                return new WallpaperResult(wallpaper, true, false);
            }
            customCacheFile.delete();
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Custom desktop wallpaper unavailable", error);
            CompatibilityDiagnostics.record(
                    "WALLPAPER-003",
                    "Custom desktop wallpaper unavailable",
                    usefulMessage(error),
                    error);
            if (customCacheFile.isFile()) {
                try {
                    return new WallpaperResult(decodeWallpaper(
                            customCacheFile,
                            targetWidth,
                            targetHeight), true, false);
                } catch (IOException cacheError) {
                    customCacheFile.delete();
                }
            }
        } finally {
            pendingFile.delete();
        }
        try {
            copySystemWallpaper(pendingFile);
            final Bitmap wallpaper = decodeWallpaper(
                    pendingFile, targetWidth, targetHeight);
            replaceCachedWallpaper(pendingFile, cacheFile);
            return new WallpaperResult(wallpaper, false, false);
        } catch (IOException | RuntimeException error) {
            Log.d(TAG, "Current system wallpaper unavailable: "
                    + usefulMessage(error));
            return cachedOrBuiltInOrFallback(
                    cacheFile, targetWidth, targetHeight);
        } finally {
            pendingFile.delete();
        }
    }

    private WallpaperResult cachedOrBuiltInOrFallback(
            final File cacheFile,
            final int targetWidth,
            final int targetHeight) {
        if (cacheFile.isFile() && cacheFile.length() > 0) {
            try {
                return new WallpaperResult(
                        decodeWallpaper(
                                cacheFile, targetWidth, targetHeight),
                        false,
                        false);
            } catch (IOException error) {
                Log.w(TAG, "Ignoring invalid cached wallpaper", error);
                cacheFile.delete();
            }
        }
        try {
            return new WallpaperResult(
                    loadBuiltInWallpaper(targetWidth, targetHeight),
                    false,
                    false);
        } catch (RuntimeException error) {
            Log.w(TAG, "Built-in wallpaper unavailable", error);
            CompatibilityDiagnostics.record(
                    "WALLPAPER-001",
                    "System wallpaper unavailable; using desktop fallback",
                    usefulMessage(error),
                    error);
        }
        return new WallpaperResult(
                createFallbackWallpaper(targetWidth, targetHeight),
                false,
                true);
    }

    private Bitmap loadBuiltInWallpaper(
            final int targetWidth,
            final int targetHeight) {
        final Drawable drawable = WallpaperManager.getInstance(mContext)
                .getBuiltInDrawable(WallpaperManager.FLAG_SYSTEM);
        if (drawable == null) {
            throw new IllegalStateException("built-in wallpaper is unavailable");
        }
        final int sourceWidth = drawable.getIntrinsicWidth();
        final int sourceHeight = drawable.getIntrinsicHeight();
        final int sampleSize = sourceWidth > 0 && sourceHeight > 0
                ? calculateSampleSize(
                        sourceWidth, sourceHeight, targetWidth, targetHeight)
                : 1;
        final int width = sourceWidth > 0
                ? Math.max(1, sourceWidth / sampleSize) : targetWidth;
        final int height = sourceHeight > 0
                ? Math.max(1, sourceHeight / sampleSize) : targetHeight;
        final Bitmap wallpaper = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(wallpaper);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
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
            throw new IOException("system wallpaper is not a decodable image");
        }

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateSampleSize(
                bounds.outWidth, bounds.outHeight, targetWidth, targetHeight);
        final Bitmap wallpaper = BitmapFactory.decodeFile(
                cacheFile.getAbsolutePath(), options);
        if (wallpaper == null) {
            throw new IOException("system wallpaper decode failed");
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

    private void copySystemWallpaper(final File destination) throws IOException {
        copyShellWallpaper(destination);
    }

    private static boolean copyCustomWallpaper(final File destination)
            throws IOException {
        final ParcelFileDescriptor descriptor =
                ShellAccess.openDesktopWallpaper();
        if (descriptor == null) {
            return false;
        }
        try (InputStream input =
                        new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                FileOutputStream output =
                        new FileOutputStream(destination, false)) {
            copy(input, output);
        }
        if (destination.length() == 0) {
            throw new IOException("custom desktop wallpaper is empty");
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

    private static void copyShellWallpaper(final File destination)
            throws IOException {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                        ShellAccess.openSystemWallpaper());
                FileOutputStream output =
                        new FileOutputStream(destination, false)) {
            copy(input, output);
        }
        if (destination.length() == 0) {
            throw new IOException("Shell wallpaper read returned no data");
        }
    }

    private static void copy(
            final InputStream input,
            final FileOutputStream output) throws IOException {
        final byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim();
    }

    private static int calculateSampleSize(final int sourceWidth, final int sourceHeight,
            final int targetWidth, final int targetHeight) {
        int sampleSize = 1;
        while (sourceWidth / (sampleSize * 2) >= targetWidth
                && sourceHeight / (sampleSize * 2) >= targetHeight) {
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
