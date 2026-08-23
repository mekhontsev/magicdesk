package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.AppLaunchTarget;
import io.github.mekhontsev.magicdesk.DesktopShellActivity;
import io.github.mekhontsev.magicdesk.DesktopUiFactory;
import io.github.mekhontsev.magicdesk.PlatformAudioCaptureDriver;
import io.github.mekhontsev.magicdesk.PlatformComponent;
import io.github.mekhontsev.magicdesk.PlatformDevice;
import io.github.mekhontsev.magicdesk.PlatformDiagnostics;
import io.github.mekhontsev.magicdesk.PlatformExtension;
import io.github.mekhontsev.magicdesk.PlatformFeatures;
import io.github.mekhontsev.magicdesk.PlatformInputRoutingDriver;
import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;
import io.github.mekhontsev.magicdesk.PlatformPointerDriver;
import io.github.mekhontsev.magicdesk.PlatformProjectionDriver;
import io.github.mekhontsev.magicdesk.PlatformMatch;
import io.github.mekhontsev.magicdesk.PlatformSystemControls;
import io.github.mekhontsev.magicdesk.PlatformTextInputDriver;
import io.github.mekhontsev.magicdesk.PlatformWallpaperDriver;
import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import android.content.Context;
import android.os.Build;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Nubia/REDMAGIC firmware implementation of the MagicDesk platform contract. */
public final class NubiaPlatformDriver implements PlatformExtension {
    private static final Set<PlatformComponent> COMPONENTS =
            Collections.unmodifiableSet(
                    EnumSet.allOf(PlatformComponent.class));
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
    public PlatformMatch match(final PlatformDevice device) {
        return match(device, NubiaFirmwareDetector.isAvailable(device));
    }

    public PlatformMatch match(
            final PlatformDevice device,
            final boolean firmwareAvailable) {
        if (device == null
                || device.sdkInt < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return PlatformMatch.unavailable("Android 15 baseline unavailable");
        }
        if (!isNubiaFamily(device)) {
            return PlatformMatch.unavailable("device family is not Nubia/REDMAGIC");
        }
        return firmwareAvailable
                ? PlatformMatch.matched(
                        "Nubia/REDMAGIC firmware marker or service detected")
                : PlatformMatch.unavailable(
                        "Nubia hardware uses standard Android firmware");
    }

    @Override
    public Set<PlatformComponent> components() {
        return COMPONENTS;
    }

    @Override
    public PlatformFeatures extendFeatures(final PlatformFeatures baseline) {
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
