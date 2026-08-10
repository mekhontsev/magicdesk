package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.util.Log;

import java.io.IOException;

final class NubiaExternalDisplayModeController {
    private static final String TAG = "MagicDeskDisplayMode";
    private static final String GETPROP = "/system/bin/getprop";
    private static final String SETPROP = "/system/bin/setprop";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String FIT_ALL_PROPERTY =
            "debug.nubia.fitalltodisplay";
    private static final String FIT_SETTING = "app_mirror_fit_status";
    private static final String SIZE_SETTING = "app_mirror_size_type";
    private NubiaExternalDisplayModeController() {
    }

    static PreparedMode prepare(
            final Context context,
            final int physicalDisplayId,
            final DisplayProfileStore.Profile profile) throws IOException {
        final boolean fillDisplay = profile == null || profile.fillDisplay;
        final String outputTiming = profile == null
                ? null : profile.outputTiming;
        final NubiaHdmiModeController.Selection selection =
                NubiaHdmiModeController.readSelection(
                        context, physicalDisplayId, outputTiming);
        final NubiaHdmiModeController.Mode requestedMode =
                selection == null ? null : selection.target;
        final boolean deferExactMode = selection != null
                && selection.requiresDeferredVendorMode();
        final int preparedDisplayId = deferExactMode
                ? physicalDisplayId
                : NubiaHdmiModeController.applyIfNeeded(
                        context, physicalDisplayId, selection);
        final int width = requestedMode != null
                ? requestedMode.width
                : 0;
        final int height = requestedMode != null
                ? requestedMode.height
                : 0;
        final int sizeType = selection == null
                ? NubiaHdmiModeController.VENDOR_SIZE_UNCHANGED
                : selection.vendorSizeType();
        final String previousBypass = readBypass();
        final PreparedMode prepared = new PreparedMode(
                previousBypass,
                preparedDisplayId,
                deferExactMode ? context : null,
                deferExactMode ? selection : null);
        try {
            writeBypass("1");
            ShellAccess.run(
                    SETTINGS + " put global " + FIT_SETTING + " "
                            + (fillDisplay ? "1" : "0"));
            if (sizeType != NubiaHdmiModeController.VENDOR_SIZE_UNCHANGED) {
                ShellAccess.run(
                        SETTINGS + " put global " + SIZE_SETTING + " "
                                + sizeType);
            }
            Log.i(TAG, "prepared Nubia output mode display=" + physicalDisplayId
                    + "->" + preparedDisplayId
                    + " physical=" + width + "x" + height
                    + (requestedMode == null
                            ? "" : "@" + requestedMode.refreshRate)
                    + " fill=" + fillDisplay
                    + " output=" + outputTiming
                    + " vendorSize=" + sizeType
                    + " deferred=" + deferExactMode);
            return prepared;
        } catch (IOException | RuntimeException error) {
            prepared.close();
            throw error;
        }
    }

    private static String readBypass() throws IOException {
        final String value = ShellAccess.run(
                GETPROP + " " + FIT_ALL_PROPERTY).trim();
        if (isBooleanProperty(value)) {
            return value;
        }
        throw new IOException(
                "unexpected " + FIT_ALL_PROPERTY + " value: " + value);
    }

    private static void writeBypass(final String value) throws IOException {
        if (!isBooleanProperty(value)) {
            throw new IllegalArgumentException("invalid fit bypass value");
        }
        ShellAccess.run(
                SETPROP + " " + FIT_ALL_PROPERTY + " '" + value + "'");
        final String observed = ShellAccess.run(
                GETPROP + " " + FIT_ALL_PROPERTY).trim();
        if (!value.equals(observed)) {
            throw new IOException(
                    "could not update " + FIT_ALL_PROPERTY
                            + ": expected=" + printable(value)
                            + " observed=" + printable(observed));
        }
    }

    static boolean isBooleanProperty(final String value) {
        return value != null
                && (value.isEmpty()
                        || "0".equals(value)
                        || "1".equals(value)
                        || "false".equals(value)
                        || "true".equals(value));
    }

    private static String printable(final String value) {
        return value.isEmpty() ? "<empty>" : value;
    }

    static final class PreparedMode implements AutoCloseable {
        private final String mPreviousBypass;
        private int mPhysicalDisplayId;
        private Context mDeferredContext;
        private NubiaHdmiModeController.Selection mDeferredSelection;
        private boolean mClosed;

        PreparedMode(
                final String previousBypass,
                final int physicalDisplayId,
                final Context deferredContext,
                final NubiaHdmiModeController.Selection deferredSelection) {
            mPreviousBypass = previousBypass;
            mPhysicalDisplayId = physicalDisplayId;
            mDeferredContext = deferredContext;
            mDeferredSelection = deferredSelection;
        }

        int physicalDisplayId() {
            return mPhysicalDisplayId;
        }

        boolean applyDeferredMode() throws IOException {
            if (mDeferredContext == null || mDeferredSelection == null) {
                return false;
            }
            final NubiaHdmiModeController.Mode target =
                    mDeferredSelection.target;
            final int currentDisplayId =
                    ConsoleDisplayController.findExternalDisplayId();
            if (currentDisplayId <= 0) {
                throw new IOException(
                        "external display disappeared during Console startup");
            }
            final NubiaHdmiModeController.Selection refreshed =
                    NubiaHdmiModeController.readSelection(
                            mDeferredContext,
                            currentDisplayId,
                            target.timingKey());
            if (refreshed == null || refreshed.target == null
                    || !target.sameTiming(refreshed.target)) {
                throw new IOException(
                        "native output timing disappeared during Console startup");
            }
            mPhysicalDisplayId = NubiaHdmiModeController.applyIfNeeded(
                    mDeferredContext, currentDisplayId, refreshed);
            mDeferredContext = null;
            mDeferredSelection = null;
            return true;
        }

        @Override
        public void close() {
            if (mClosed) {
                return;
            }
            mClosed = true;
            try {
                writeBypass(mPreviousBypass);
            } catch (IOException error) {
                Log.w(TAG, "Cannot restore Nubia display-fit bypass", error);
                CompatibilityDiagnostics.record(
                        "NUBIA-DISPLAY-002",
                        "Could not restore the external display compatibility flag",
                        error.getMessage(),
                        error);
            }
        }
    }
}
