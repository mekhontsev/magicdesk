package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.util.List;
import java.util.function.Consumer;

/** Firmware boundary composed with the independent display-driver boundary. */
public interface PlatformDriver {
    String id();

    String name();

    boolean supports(PlatformDevice device);

    PlatformSupportLevel supportLevel(PlatformDevice device);

    PlatformFeatures features();

    PlatformWindowingDriver windowing();

    PlatformPointerDriver pointer();

    PlatformProjectionDriver projection();

    PlatformPhoneUiDriver phoneUi();

    PlatformWallpaperDriver wallpaper();

    PlatformDiagnostics diagnostics();

    PlatformAudioCaptureDriver audioCapture();

    PlatformTextInputDriver textInput();

    PlatformInputRoutingDriver inputRouting();

    PlatformSystemControls createSystemControls(
            DesktopShellActivity activity,
            DesktopUiFactory ui);

    List<AppLaunchTarget> additionalLaunchTargets();

    void startRuntime(Context context);

    void stopRuntime();

    void restoreRuntimeState(Consumer<Boolean> callback);
}
