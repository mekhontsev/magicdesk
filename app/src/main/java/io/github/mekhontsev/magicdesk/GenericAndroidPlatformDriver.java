package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Build;

/** Conservative Android fallback: local and simulated desktops only. */
final class GenericAndroidPlatformDriver implements PlatformDriver {
    private static final PlatformFeatures FEATURES = new PlatformFeatures(
            false, false, false, false, false, false, false);
    private static final PlatformWindowingDriver WINDOWING =
            new GenericAndroidWindowingDriver();
    private static final PlatformPointerDriver POINTER =
            new GenericAndroidPointerDriver();

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
    public void startRuntime(final Context context) {
    }

    @Override
    public void stopRuntime() {
    }
}
