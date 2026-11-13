package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.CompatibilityDiagnostics;
import io.github.mekhontsev.magicdesk.DesktopWindowingProperties;
import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;
import io.github.mekhontsev.magicdesk.ShellAccess;
import io.github.mekhontsev.magicdesk.ShellCommandLine;

import java.io.IOException;
import java.util.function.BiConsumer;

/** Best-effort desktop properties; actual window support is tested separately. */
final class NubiaWindowingDriver implements PlatformWindowingDriver {
    @FunctionalInterface
    interface PropertyWriter {
        boolean write(DesktopWindowingProperties.Property property, String value)
                throws IOException;
    }

    private final PropertyWriter mWriter;
    private final BiConsumer<DesktopWindowingProperties.Property, Exception> mWarning;

    NubiaWindowingDriver() {
        this((property, value) -> DesktopWindowingProperties.write(
                property, value, NubiaWindowingDriver::writeProperty), (property, error) ->
                CompatibilityDiagnostics.record("NUBIA-SETUP-001",
                        "Could not apply optional desktop property",
                        property.key + ": " + error.getMessage(), error));
    }

    NubiaWindowingDriver(final PropertyWriter writer,
            final BiConsumer<DesktopWindowingProperties.Property, Exception> warning) {
        mWriter = writer;
        mWarning = warning;
    }

    @Override
    public boolean supportsIndependentFreeform() {
        // Vendor DesktopRepository and phone Recents need managed-session recovery.
        return false;
    }

    @Override
    public String restrictionsPropertyKey() {
        return DesktopWindowingProperties.Property.DEVICE_RESTRICTIONS.key;
    }

    @Override
    public String roundedCornersPropertyKey() {
        return DesktopWindowingProperties.Property.ROUNDED_CORNERS.key;
    }

    @Override
    public boolean canChangeDeviceRestrictions() { return true; }

    @Override
    public boolean deviceRestrictionsEnabled() throws IOException {
        return !"false".equals(DesktopWindowingProperties.read(
                DesktopWindowingProperties.Property.DEVICE_RESTRICTIONS));
    }

    @Override
    public void setDeviceRestrictionsEnabled(final boolean enabled) throws IOException {
        mWriter.write(DesktopWindowingProperties.Property.DEVICE_RESTRICTIONS,
                Boolean.toString(enabled));
    }

    @Override
    public boolean configure(
            final boolean restrictionsDisabled,
            final boolean roundedCornersDisabled) {
        boolean changed = false;
        if (!restrictionsDisabled) {
            changed = writeOptional(
                    DesktopWindowingProperties.Property.DEVICE_RESTRICTIONS,
                    "false");
        }
        if (!roundedCornersDisabled) {
            changed |= writeOptional(
                    DesktopWindowingProperties.Property.ROUNDED_CORNERS,
                    "false");
        }
        return changed;
    }

    @Override
    public void restoreDefaults() {
        for (final DesktopWindowingProperties.Property property
                : DesktopWindowingProperties.Property.values()) {
            writeOptional(property, "");
        }
    }

    private boolean writeOptional(final DesktopWindowingProperties.Property property,
            final String value) {
        try {
            return mWriter.write(property, value);
        } catch (IOException | RuntimeException error) {
            mWarning.accept(property, error);
            return false;
        }
    }

    private static String writeProperty(final DesktopWindowingProperties.Property property,
            final String value) throws IOException {
        // Newer firmware checks WRITE_SECURE_SETTINGS before clearing Binder identity.
        // Use the selected command service, never an app-UID process or a root retry.
        return ShellAccess.run(propertyWriteCommand(property, value));
    }

    static String propertyWriteCommand(final DesktopWindowingProperties.Property property,
            final String value) {
        return "/system/bin/service call redmagic.app.manager 2 s16 "
                + ShellCommandLine.quote(property.key) + " s16 " + ShellCommandLine.quote(value);
    }
}
