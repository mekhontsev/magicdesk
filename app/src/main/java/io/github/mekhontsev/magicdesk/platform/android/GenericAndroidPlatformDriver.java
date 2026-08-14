package io.github.mekhontsev.magicdesk.platform.android;

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

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Standard Android baseline with optional vendor integrations disabled. */
public final class GenericAndroidPlatformDriver implements PlatformDriver {
    private static final PlatformFeatures FEATURES = new PlatformFeatures(
            true, true, false, false);
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
    private static final PlatformAudioCaptureDriver AUDIO_CAPTURE =
            new GenericAndroidAudioCaptureDriver();
    private static final PlatformTextInputDriver TEXT_INPUT =
            new GenericAndroidTextInputDriver();
    private static final PlatformInputRoutingDriver INPUT_ROUTING =
            new GenericAndroidInputRoutingDriver();

    @Override
    public String id() {
        return "android";
    }

    @Override
    public String name() {
        return "Standard Android";
    }

    @Override
    public boolean supports(final PlatformDevice device) {
        return device != null
                && device.sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM;
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
    public PlatformAudioCaptureDriver audioCapture() {
        return AUDIO_CAPTURE;
    }

    @Override
    public PlatformTextInputDriver textInput() {
        return TEXT_INPUT;
    }

    @Override
    public PlatformInputRoutingDriver inputRouting() {
        return INPUT_ROUTING;
    }

    @Override
    public PlatformSystemControls createSystemControls(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        return PlatformSystemControls.NONE;
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

    @Override
    public void restoreRuntimeState(final Consumer<Boolean> callback) {
        if (callback != null) {
            callback.accept(Boolean.TRUE);
        }
    }
}
