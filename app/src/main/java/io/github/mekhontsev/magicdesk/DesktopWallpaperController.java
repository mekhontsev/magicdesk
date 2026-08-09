package io.github.mekhontsev.magicdesk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

    DesktopWallpaperController(
            final Context context,
            final ImageView wallpaperView) {
        mContext = context.getApplicationContext();
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
        mLoadGeneration.incrementAndGet();
        try {
            mContext.unregisterReceiver(mWallpaperChangedReceiver);
        } catch (IllegalArgumentException ignored) {
            // The process may already have detached the receiver.
        }
        mExecutor.shutdownNow();
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
                    final Bitmap wallpaper = loadWallpaper(targetWidth, targetHeight);
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!mStarted || generation != mLoadGeneration.get()) {
                                wallpaper.recycle();
                                return;
                            }
                            mWallpaperView.setImageBitmap(wallpaper);
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

    private Bitmap loadWallpaper(
            final int targetWidth,
            final int targetHeight) {
        final File cacheFile = new File(mContext.getCacheDir(), "desktop-wallpaper");
        if (!ShellAccess.isReady()) {
            return cachedOrFallback(
                    cacheFile, targetWidth, targetHeight);
        }
        final File pendingFile = new File(
                mContext.getCacheDir(), "desktop-wallpaper.pending");
        try {
            copySystemWallpaper(pendingFile);
            final Bitmap wallpaper = decodeWallpaper(
                    pendingFile, targetWidth, targetHeight);
            replaceCachedWallpaper(pendingFile, cacheFile);
            return wallpaper;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "System wallpaper unavailable; using fallback", error);
            CompatibilityDiagnostics.record(
                    "WALLPAPER-001",
                    "System wallpaper unavailable; using desktop fallback",
                    usefulMessage(error),
                    error);
            return cachedOrFallback(
                    cacheFile, targetWidth, targetHeight);
        } finally {
            pendingFile.delete();
        }
    }

    private Bitmap cachedOrFallback(
            final File cacheFile,
            final int targetWidth,
            final int targetHeight) {
        if (cacheFile.isFile() && cacheFile.length() > 0) {
            try {
                return decodeWallpaper(cacheFile, targetWidth, targetHeight);
            } catch (IOException error) {
                Log.w(TAG, "Ignoring invalid cached wallpaper", error);
            }
        }
        return createFallbackWallpaper(targetWidth, targetHeight);
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

}
