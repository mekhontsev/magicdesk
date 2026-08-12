package io.github.mekhontsev.magicdesk;

import android.os.Build;

import java.util.Locale;

/** Immutable device identity used to select a platform driver. */
final class PlatformDevice {
    final String manufacturer;
    final String brand;
    final String model;
    final String device;
    final String product;
    final String fingerprint;
    final int sdkInt;

    PlatformDevice(
            final String manufacturer,
            final String brand,
            final String model,
            final String device,
            final String product,
            final String fingerprint,
            final int sdkInt) {
        this.manufacturer = safe(manufacturer);
        this.brand = safe(brand);
        this.model = safe(model);
        this.device = safe(device);
        this.product = safe(product);
        this.fingerprint = safe(fingerprint);
        this.sdkInt = sdkInt;
    }

    static PlatformDevice current() {
        return new PlatformDevice(
                Build.MANUFACTURER,
                Build.BRAND,
                Build.MODEL,
                Build.DEVICE,
                Build.PRODUCT,
                Build.FINGERPRINT,
                Build.VERSION.SDK_INT);
    }

    boolean familyNameContains(final String token) {
        final String normalized = token.toLowerCase(Locale.US);
        return contains(manufacturer, normalized)
                || contains(brand, normalized)
                || contains(product, normalized);
    }

    private static boolean contains(
            final String value,
            final String normalizedToken) {
        return value.toLowerCase(Locale.US).contains(normalizedToken);
    }

    private static String safe(final String value) {
        return value == null ? "" : value;
    }
}
