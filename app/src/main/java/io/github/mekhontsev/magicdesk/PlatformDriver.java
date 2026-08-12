package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.util.List;

/** Firmware boundary composed with the independent display-driver boundary. */
interface PlatformDriver {
    String id();

    String name();

    boolean supports(PlatformDevice device);

    PlatformSupportLevel supportLevel(PlatformDevice device);

    PlatformFeatures features();

    PlatformWindowingDriver windowing();

    PlatformPointerDriver pointer();

    PlatformProjectionDriver projection();

    PlatformPhoneUiDriver phoneUi();

    PlatformDiagnostics diagnostics();

    List<AppLaunchTarget> additionalLaunchTargets();

    void startRuntime(Context context);

    void stopRuntime();
}
