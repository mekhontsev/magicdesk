package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.AppLaunchTarget;
import io.github.mekhontsev.magicdesk.DesktopShellActivity;
import io.github.mekhontsev.magicdesk.DesktopUiFactory;
import io.github.mekhontsev.magicdesk.PlatformAudioCaptureDriver;
import io.github.mekhontsev.magicdesk.PlatformDevice;
import io.github.mekhontsev.magicdesk.PlatformDiagnostics;
import io.github.mekhontsev.magicdesk.PlatformDriver;
import io.github.mekhontsev.magicdesk.PlatformFeatures;
import io.github.mekhontsev.magicdesk.PlatformInputRoutingDriver;
import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;
import io.github.mekhontsev.magicdesk.PlatformPointerDriver;
import io.github.mekhontsev.magicdesk.PlatformProjectionDriver;
import io.github.mekhontsev.magicdesk.PlatformSupportLevel;
import io.github.mekhontsev.magicdesk.PlatformSystemControls;
import io.github.mekhontsev.magicdesk.PlatformTextInputDriver;
import io.github.mekhontsev.magicdesk.PlatformWallpaperDriver;
import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import android.content.Context;
import android.os.Build;

import java.util.List;
import java.util.function.Consumer;

/** Nubia/REDMAGIC firmware implementation of the MagicDesk platform contract. */
public final class NubiaPlatformDriver implements PlatformDriver {
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
            true, true, true, true);
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
    private static final PlatformAudioCaptureDriver AUDIO_CAPTURE =
            new NubiaAudioCaptureDriver();
    private static final PlatformInputRoutingDriver INPUT_ROUTING =
            new NubiaInputRoutingDriver();

    @Override
    public String id() {
        return "nubia";
    }

    @Override
    public String name() {
        return "Nubia/REDMAGIC firmware";
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
    public PlatformAudioCaptureDriver audioCapture() {
        return AUDIO_CAPTURE;
    }

    @Override
    public PlatformTextInputDriver textInput() {
        return NubiaMirrorTextInputDriver.INSTANCE;
    }

    @Override
    public PlatformInputRoutingDriver inputRouting() {
        return INPUT_ROUTING;
    }

    @Override
    public PlatformSystemControls createSystemControls(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        return new NubiaSystemControls(activity, ui);
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

    @Override
    public void restoreRuntimeState(final Consumer<Boolean> callback) {
        RedmagicHardwareController.restoreChangedState(
                success -> {
                    if (callback != null) {
                        callback.accept(Boolean.valueOf(success));
                    }
                });
    }

    static boolean isNubiaFamily(final PlatformDevice device) {
        return device.familyNameContains("nubia")
                || device.familyNameContains("redmagic");
    }
}
