package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Firmware boundary composed with the independent display-driver boundary. */
interface PlatformDriver {
    String id();

    String name();

    boolean supports(PlatformDevice device);

    PlatformSupportLevel supportLevel(PlatformDevice device);

    PlatformFeatures features();

    PlatformWindowingDriver windowing();

    PlatformPointerDriver pointer();

    void startRuntime(Context context);

    void stopRuntime();
}
