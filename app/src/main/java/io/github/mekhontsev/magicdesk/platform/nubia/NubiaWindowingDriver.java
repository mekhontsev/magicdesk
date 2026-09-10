package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.CompatibilityDiagnostics;
import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import java.io.IOException;
import java.util.function.BiConsumer;

/** Best-effort desktop properties; actual window support is tested separately. */
final class NubiaWindowingDriver implements PlatformWindowingDriver {
    @FunctionalInterface
    interface PropertyWriter {
        boolean write(NubiaDesktopPropertyManager.Property property, String value)
                throws IOException;
    }

    private final PropertyWriter mWriter;
    private final BiConsumer<NubiaDesktopPropertyManager.Property, Exception> mWarning;

    NubiaWindowingDriver() {
        this(NubiaDesktopPropertyManager::write, (property, error) ->
                CompatibilityDiagnostics.record("NUBIA-SETUP-001",
                        "Could not apply optional desktop property",
                        property.key + ": " + error.getMessage(), error));
    }

    NubiaWindowingDriver(final PropertyWriter writer,
            final BiConsumer<NubiaDesktopPropertyManager.Property, Exception> warning) {
        mWriter = writer;
        mWarning = warning;
    }

    @Override
    public String restrictionsPropertyKey() {
        return NubiaDesktopPropertyManager.Property.DEVICE_RESTRICTIONS.key;
    }

    @Override
    public String roundedCornersPropertyKey() {
        return NubiaDesktopPropertyManager.Property.ROUNDED_CORNERS.key;
    }

    @Override
    public boolean configure(
            final boolean restrictionsDisabled,
            final boolean roundedCornersDisabled) {
        boolean changed = false;
        if (!restrictionsDisabled) {
            changed = writeOptional(
                    NubiaDesktopPropertyManager.Property.DEVICE_RESTRICTIONS,
                    "false");
        }
        if (!roundedCornersDisabled) {
            changed |= writeOptional(
                    NubiaDesktopPropertyManager.Property.ROUNDED_CORNERS,
                    "false");
        }
        return changed;
    }

    @Override
    public void restoreDefaults() {
        for (final NubiaDesktopPropertyManager.Property property
                : NubiaDesktopPropertyManager.Property.values()) {
            writeOptional(property, "");
        }
    }

    private boolean writeOptional(final NubiaDesktopPropertyManager.Property property,
            final String value) {
        try {
            return mWriter.write(property, value);
        } catch (IOException | RuntimeException error) {
            mWarning.accept(property, error);
            return false;
        }
    }
}
