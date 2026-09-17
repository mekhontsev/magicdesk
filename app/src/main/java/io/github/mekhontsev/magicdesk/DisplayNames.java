package io.github.mekhontsev.magicdesk;

import android.hardware.display.DeviceProductInfo;
import android.view.Display;

/** User-facing names, independent of display identity and profile keys. */
final class DisplayNames {
    private DisplayNames() { }

    static String name(final Display display) {
        final DeviceProductInfo product = display.getDeviceProductInfo();
        if (product != null) {
            final String name = product.getName();
            if (name != null && !name.trim().isEmpty()) return name.trim();
        }
        return display.getName();
    }
}
