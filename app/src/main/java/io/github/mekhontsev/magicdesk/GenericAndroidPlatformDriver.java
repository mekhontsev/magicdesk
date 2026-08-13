package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Build;

import java.util.Collections;
import java.util.List;

/** Conservative Android fallback: local and simulated desktops only. */
final class GenericAndroidPlatformDriver implements PlatformDriver {
    private static final PlatformFeatures FEATURES = new PlatformFeatures(
            false, false, false);
    private static final PlatformWindowingDriver WINDOWING =
            new GenericAndroidWindowingDriver();
    private static final PlatformPointerDriver POINTER =
            new GenericAndroidPointerDriver();
    private static final PlatformProjectionDriver PROJECTION =
            new GenericAndroidProjectionDriver();
    private static final PlatformPhoneUiDriver PHONE_UI =
            new GenericAndroidPhoneUiDriver();
    private static final PlatformWallpaperDriver WALLPAPER =
            new GenericAndroidWallpaperDriver();
    private static final PlatformDiagnostics DIAGNOSTICS =
            new GenericAndroidPlatformDiagnostics();

    @Override
    public String id() {
        return "android";
    }

    @Override
    public String name() {
        return "Generic Android";
    }

    @Override
    public boolean supports(final PlatformDevice device) {
        return device != null && device.sdkInt >= Build.VERSION_CODES.BAKLAVA;
    }

    @Override
    public PlatformSupportLevel supportLevel(final PlatformDevice device) {
        return PlatformSupportLevel.UNVERIFIED;
    }

    @Override
    public PlatformFeatures features() {
        return FEATURES;
    }

    @Override
    public PlatformWindowingDriver windowing() {
        return WINDOWING;
    }

    @Override
    public PlatformPointerDriver pointer() {
        return POINTER;
    }

    @Override
    public PlatformProjectionDriver projection() {
        return PROJECTION;
    }

    @Override
    public PlatformPhoneUiDriver phoneUi() {
        return PHONE_UI;
    }

    @Override
    public PlatformWallpaperDriver wallpaper() {
        return WALLPAPER;
    }

    @Override
    public PlatformDiagnostics diagnostics() {
        return DIAGNOSTICS;
    }

    @Override
    public List<AppLaunchTarget> additionalLaunchTargets() {
        return Collections.emptyList();
    }

    @Override
    public void startRuntime(final Context context) {
    }

    @Override
    public void stopRuntime() {
    }
}
