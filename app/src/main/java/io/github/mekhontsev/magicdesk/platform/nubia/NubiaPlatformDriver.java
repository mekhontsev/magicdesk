package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.AppLaunchTarget;
import io.github.mekhontsev.magicdesk.DesktopShellActivity;
import io.github.mekhontsev.magicdesk.DesktopUiFactory;
import io.github.mekhontsev.magicdesk.DesktopCompatibilityPolicy;
import io.github.mekhontsev.magicdesk.DesktopCompatibilityPolicy.Option;
import io.github.mekhontsev.magicdesk.PlatformAudioCaptureDriver;
import io.github.mekhontsev.magicdesk.PlatformComponent;
import io.github.mekhontsev.magicdesk.PlatformDevice;
import io.github.mekhontsev.magicdesk.PlatformDiagnostics;
import io.github.mekhontsev.magicdesk.PlatformExtension;
import io.github.mekhontsev.magicdesk.PlatformFeatures;
import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;
import io.github.mekhontsev.magicdesk.PlatformPointerDriver;
import io.github.mekhontsev.magicdesk.PlatformProjectionDriver;
import io.github.mekhontsev.magicdesk.PlatformMatch;
import io.github.mekhontsev.magicdesk.PlatformSystemControls;
import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import android.content.Context;
import android.os.Build;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Nubia/REDMAGIC firmware implementation of the MagicDesk platform contract. */
public final class NubiaPlatformDriver implements PlatformExtension {
    private static final PlatformWindowingDriver WINDOWING =
            new NubiaWindowingDriver();
    private static final PlatformPointerDriver POINTER =
            new NubiaDesktopPointerDriver();
    private static final PlatformProjectionDriver PROJECTION =
            new NubiaProjectionDriver();
    private static final PlatformPhoneUiDriver PHONE_UI =
            new NubiaPhoneUiDriver();
    private static final PlatformAudioCaptureDriver AUDIO_CAPTURE =
            new NubiaAudioCaptureDriver();

    private final NubiaFirmwareDetector.Result mCapabilities;
    private final PlatformDiagnostics mDiagnostics;

    public NubiaPlatformDriver(
            final NubiaFirmwareDetector.Result capabilities) {
        if (capabilities == null) {
            throw new IllegalArgumentException(
                    "Nubia firmware capabilities are required");
        }
        mCapabilities = capabilities;
        mDiagnostics = new NubiaPlatformDiagnostics(
                capabilities.components().contains(PlatformComponent.WINDOWING));
    }

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
        if (device == null
                || device.sdkInt < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return PlatformMatch.unavailable("Android 15 baseline unavailable");
        }
        if (!isNubiaFamily(device)) {
            return PlatformMatch.unavailable("device family is not Nubia/REDMAGIC");
        }
        return mCapabilities.isAvailable()
                ? PlatformMatch.matched(
                        mCapabilities.summary())
                : PlatformMatch.unavailable(
                        "Nubia hardware uses standard Android firmware");
    }

    @Override
    public Set<PlatformComponent> components() {
        return mCapabilities.components();
    }

    @Override
    public String componentEvidence(final PlatformComponent component) {
        return mCapabilities.evidence(component);
    }

    @Override
    public PlatformFeatures extendFeatures(final PlatformFeatures baseline) {
        DesktopCompatibilityPolicy defaults = baseline.compatibilityDefaults;
        if (components().contains(PlatformComponent.WINDOWING)) {
            // These shared mechanisms address observed task failures in
            // stock firmware. Optional vendor APIs alone do not select them.
            defaults = defaults.with(Option.CAPTION_REFRESH, true)
                    .with(Option.PHONE_TASK_ISOLATION, true)
                    .with(Option.PHONE_TASK_RECOVERY, true)
                    .with(Option.STALE_RECENTS_CLEANUP, true);
        }
        if (components().contains(PlatformComponent.PHONE_UI)) {
            defaults = defaults.with(Option.RECENTS_TO_HOME, true);
        }
        return new PlatformFeatures(
                baseline.wiredDesktop,
                baseline.wirelessDesktop,
                defaults,
                baseline.vendorHardware
                        || components().contains(
                                PlatformComponent.SYSTEM_CONTROLS));
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
    public PlatformDiagnostics diagnostics() {
        return mDiagnostics;
    }

    @Override
    public PlatformAudioCaptureDriver audioCapture() {
        return AUDIO_CAPTURE;
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
