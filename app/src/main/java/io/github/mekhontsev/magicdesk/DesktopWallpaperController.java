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
    interface Listener {
        void onWallpaperLoadFailed(String message);
    }

    private static final String TAG = "MagicDeskWallpaper";
    private static final int BUFFER_SIZE = 32 * 1024;

    private final Context mContext;
    private final ImageView mWallpaperView;
    private final Listener mListener;
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

    DesktopWallpaperController(final Context context, final ImageView wallpaperView,
            final Listener listener) {
        mContext = context.getApplicationContext();
        mWallpaperView = wallpaperView;
        mListener = listener;
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
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "Cannot load system wallpaper", e);
                    final String message = e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage();
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mStarted && generation == mLoadGeneration.get()
                                    && mListener != null) {
                                mListener.onWallpaperLoadFailed(message);
                            }
                        }
                    });
                }
            }
        });
    }

    private Bitmap loadWallpaper(final int targetWidth, final int targetHeight)
            throws IOException {
        final File cacheFile = new File(mContext.getCacheDir(), "desktop-wallpaper");
        if (!ShellAccess.isReady()) {
            if (cacheFile.isFile() && cacheFile.length() > 0) {
                try {
                    return decodeWallpaper(cacheFile, targetWidth, targetHeight);
                } catch (IOException error) {
                    Log.w(TAG, "Ignoring invalid cached wallpaper", error);
                }
            }
            return createFallbackWallpaper(targetWidth, targetHeight);
        }
        copySystemWallpaper(cacheFile);
        return decodeWallpaper(cacheFile, targetWidth, targetHeight);
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
