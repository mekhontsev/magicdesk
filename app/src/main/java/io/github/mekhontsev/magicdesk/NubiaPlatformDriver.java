package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Build;

import java.util.List;

/** ZTE/nubia firmware implementation of the MagicDesk platform contract. */
final class NubiaPlatformDriver implements PlatformDriver {
    private static final String MAINTAINER_VERIFIED_NX809J_FINGERPRINT =
            "REDMAGIC/NX809J-EEA/NX809J:16/"
                    + "BQ2A.250705.001-BP2A.250605.031.A3/"
                    + "20260204.221845:user/release-keys";
    private static final String COMMUNITY_TESTED_NX809J_FINGERPRINT =
            "REDMAGIC/NX809J-UN/NX809J:16/"
                    + "BQ2A.250705.001-BP2A.250605.031.A3/"
                    + "20260625.022314:user/release-keys";
    private static final String COMMUNITY_TESTED_NX741J_FINGERPRINT =
            "nubia/PQ85A01-UN/PQ85A01:16/"
                    + "BQ2A.250705.001-BP2A.250605.031.A3/"
                    + "20251229.234747:user/release-keys";
    private static final PlatformFeatures FEATURES = new PlatformFeatures(
            true, true, true);
    private static final PlatformWindowingDriver WINDOWING =
            new NubiaWindowingDriver();
    private static final PlatformPointerDriver POINTER =
            new NubiaPointerDriver();
    private static final PlatformProjectionDriver PROJECTION =
            new NubiaProjectionDriver();
    private static final PlatformPhoneUiDriver PHONE_UI =
            new NubiaPhoneUiDriver();
    private static final PlatformWallpaperDriver WALLPAPER =
            new NubiaWallpaperDriver();
    private static final PlatformDiagnostics DIAGNOSTICS =
            new NubiaPlatformDiagnostics();

    @Override
    public String id() {
        return "nubia";
    }

    @Override
    public String name() {
        return "ZTE/nubia firmware";
    }

    @Override
    public boolean supports(final PlatformDevice device) {
        return device != null
                && device.sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                && isNubiaFamily(device);
    }

    @Override
    public PlatformSupportLevel supportLevel(final PlatformDevice device) {
        if (device == null) {
            return PlatformSupportLevel.UNVERIFIED;
        }
        final boolean nx809j = "NX809J".equalsIgnoreCase(device.model)
                || "NX809J".equalsIgnoreCase(device.device);
        if (nx809j && MAINTAINER_VERIFIED_NX809J_FINGERPRINT.equals(
                device.fingerprint)) {
            return PlatformSupportLevel.MAINTAINER_VERIFIED;
        }
        final boolean nx741j = "NX741J".equalsIgnoreCase(device.model)
                || "PQ85A01".equalsIgnoreCase(device.device);
        if ((nx809j && COMMUNITY_TESTED_NX809J_FINGERPRINT.equals(
                device.fingerprint))
                || (nx741j && COMMUNITY_TESTED_NX741J_FINGERPRINT.equals(
                device.fingerprint))) {
            return PlatformSupportLevel.COMMUNITY_TESTED;
        }
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
        return RedmagicEntryPointCatalog.targets();
    }

    @Override
    public void startRuntime(final Context context) {
        RedmagicHardwareController.start(context);
    }

    @Override
    public void stopRuntime() {
        RedmagicHardwareController.stop();
    }

    static boolean isNubiaFamily(final PlatformDevice device) {
        return device.familyNameContains("zte")
                || device.familyNameContains("nubia")
                || device.familyNameContains("redmagic");
    }
}
