package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.util.DisplayMetrics;
import android.util.Log;

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

    private final DesktopShellActivity mActivity;
    private final Context mContext;
    private final WallpaperView mWallpaperView;
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
    private WallpaperAsset mThemeWallpaper;
    private final Runnable mAppearanceChanged = this::appearanceChanged;

    private void appearanceChanged() {
        if (AppearanceStore.assets(mActivity).wallpaper() != mThemeWallpaper) reload();
    }

    DesktopWallpaperController(final DesktopShellActivity activity) {
        mActivity = activity;
        mContext = activity.getApplicationContext();
        mWallpaperView = new WallpaperView(activity, mExecutor);
    }

    WallpaperView view() { return mWallpaperView; }

    void start() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        AppearanceStore.listen(mAppearanceChanged);
        reload();
    }

    void stop() {
        if (!mStarted) {
            return;
        }
        mStarted = false;
        AppearanceStore.unlisten(mAppearanceChanged);
        mRendered = false;
        mLoadGeneration.incrementAndGet();
        mWallpaperView.close();
        mExecutor.shutdownNow();
    }

    void useDefaultWallpaper() {
        if (!mStarted) {
            return;
        }
        try {
            final String scope = AppearanceScopeBindings.find(mActivity);
            final ShellAppearance theme = AppearanceStore.current(mActivity);
            if (!theme.resources().wallpaper().isEmpty() || theme.resources().shader() != null) {
                if (scope == null) {
                    var r = theme.resources();
                    AppearanceStore.apply(theme.withResources(new ShellResources(r.icons(), r.bundle(), r.iconAssets(), r.font(), "", null, r.fit())));
                } else {
                    var patch = new org.json.JSONObject(AppearanceStore.snapshot(scope).patch());
                    var resources = patch.optJSONObject("resources");
                    if (resources == null) { resources = new org.json.JSONObject(); patch.put("resources", resources); }
                    resources.put("wallpaper", "");
                    resources.put("shader", org.json.JSONObject.NULL);
                    AppearanceStore.apply(scope, patch.toString());
                }
            }
        } catch (org.json.JSONException | RuntimeException error) {
            mActivity.setErrorStatus("WALLPAPER-003", mActivity.getString(R.string.status_desktop_wallpaper_failed,
                    usefulMessage(error)), "appearance wallpaper", error);
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
        final WallpaperAsset themeWallpaper = AppearanceStore.assets(mActivity).wallpaper();
        mThemeWallpaper = themeWallpaper;
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
                    final WallpaperResult result = themeWallpaper == null ? loadWallpaper(
                            targetWidth, targetHeight, cancelled) : new WallpaperResult(themeWallpaper, true, false);
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        if (themeWallpaper == null) result.asset.poster().recycle();
                        return;
                    }
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!mStarted || generation != mLoadGeneration.get()) {
                                if (themeWallpaper == null) result.asset.poster().recycle();
                                return;
                            }
                            mUsingCustomWallpaper = result.custom;
                            mUsingFallbackWallpaper = result.fallback;
                            mWallpaperView.show(result.asset, targetWidth, targetHeight, () -> {
                                if (mStarted && generation == mLoadGeneration.get() && !mActivity.isActivityUnavailable()) {
                                    mRendered = true;
                                    recordRenderedEvent(result);
                                }
                            }, state -> recordPlaybackEvent(result, state), error -> {
                                mUsingFallbackWallpaper = true;
                                CompatibilityDiagnostics.record("WALLPAPER-004", "Wallpaper playback failed; showing static frame", usefulMessage(error), error);
                            });
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

    private void recordRenderedEvent(final WallpaperResult result) {
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
                            .put("mediaKind", result.asset.kind().name().toLowerCase(java.util.Locale.ROOT))
                            .put("bitmapWidth", result.asset.poster().getWidth())
                            .put("bitmapHeight", result.asset.poster().getHeight())
                            .put("bitmapDensity", result.asset.poster().getDensity())
                            .put("viewWidth", mWallpaperView.getWidth())
                            .put("viewHeight", mWallpaperView.getHeight())
                            .put("displayDensity", mWallpaperView.getResources()
                                    .getDisplayMetrics().densityDpi)
                            .put("fit", AppearanceStore.current(mActivity).resources().fit()
                                    .name().toLowerCase(java.util.Locale.ROOT)));
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "ui", "wallpaper_rendered", true,
                    "display=" + mActivity.getCurrentDisplayId());
        }
    }

    private void recordPlaybackEvent(WallpaperResult result, String state) {
        try {
            DesktopAutomationEventJournal.record("ui", "wallpaper_playback", true, state,
                    new org.json.JSONObject().put("displayId", mActivity.getCurrentDisplayId())
                            .put("mediaKind", result.asset.kind().name().toLowerCase(java.util.Locale.ROOT)).put("state", state));
        } catch (org.json.JSONException ignored) { }
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
        final WallpaperAsset wallpaper = decodeWallpaper(pendingFile, targetWidth, targetHeight);
        try {
            ContentStreamCopy.checkCancelled(cancelled);
            replaceCachedWallpaper(pendingFile, cacheFile);
            return new WallpaperResult(wallpaper, true, false);
        } catch (IOException | RuntimeException | Error error) {
            wallpaper.poster().recycle();
            throw error;
        }
    }

    static File createPendingFile(final File directory) throws IOException {
        // Controllers may overlap while their HOME hosts are being replaced.
        return File.createTempFile("desktop-wallpaper-", ".pending", directory);
    }

    private WallpaperResult defaultWallpaper(
            final int targetWidth,
            final int targetHeight) {
        try {
            return new WallpaperResult(
                    WallpaperAsset.image(loadBundledWallpaper(targetWidth, targetHeight)),
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
                WallpaperAsset.image(createFallbackWallpaper(targetWidth, targetHeight)),
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
        options.inSampleSize = WallpaperPolicy.calculateSampleSize(
                bounds.outWidth, bounds.outHeight, targetWidth, targetHeight);
        final Bitmap wallpaper = BitmapFactory.decodeResource(
                mContext.getResources(), R.drawable.desktop_wallpaper, options);
        if (wallpaper == null) {
            throw new IllegalStateException("bundled wallpaper decode failed");
        }
        return wallpaper;
    }

    private WallpaperAsset decodeWallpaper(
            final File cacheFile,
            final int targetWidth,
            final int targetHeight) throws IOException {
        return WallpaperAsset.decode(ThemeBundleFiles.read(cacheFile.toPath(), ShellDesktopDirectory.MAX_WALLPAPER_BYTES),
                targetWidth, targetHeight);
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

    private static final class WallpaperResult {
        final WallpaperAsset asset;
        final boolean custom;
        final boolean fallback;

        WallpaperResult(
                final WallpaperAsset asset,
                final boolean custom,
                final boolean fallback) {
            this.asset = asset;
            this.custom = custom;
            this.fallback = fallback;
        }
    }

}
