package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.io.IOException;

/** Owns the overlay display used when no physical desktop display is present. */
final class SimulatedDesktopDisplayController {
    private static final String TAG = "MagicDeskSimulatedDisplay";

    private static SimulatedDisplayLease sLease;
    private static int sDisplayId = Display.INVALID_DISPLAY;

    private SimulatedDesktopDisplayController() {
    }

    static void show() {
        int displayId = Display.INVALID_DISPLAY;
        try {
            displayId = acquire();
            DesktopDisplayDrivers
                    .forKind(DesktopDisplayTarget.Kind.SIMULATED)
                    .show(null, displayId);
            if (DesktopRuntimeBridge.getDesktopTarget(displayId) == null) {
                release(displayId);
            }
        } catch (IOException | RuntimeException error) {
            if (displayId > Display.DEFAULT_DISPLAY) {
                release(displayId);
            }
            Log.w(TAG, "Could not open the simulated desktop", error);
            CompatibilityDiagnostics.record(
                    "DISPLAY-SIMULATED-001",
                    "Could not open the simulated desktop",
                    usefulMessage(error),
                    error);
        }
    }

    static void release(final int displayId) {
        final SimulatedDisplayLease lease;
        synchronized (SimulatedDesktopDisplayController.class) {
            if (sDisplayId != displayId) {
                return;
            }
            sDisplayId = Display.INVALID_DISPLAY;
            lease = sLease;
            sLease = null;
        }
        if (lease == null) {
            return;
        }
        try {
            lease.close();
        } catch (IOException error) {
            Log.w(TAG, "Could not remove the simulated display", error);
            CompatibilityDiagnostics.record(
                    "DISPLAY-SIMULATED-002",
                    "Could not remove the simulated desktop display",
                    usefulMessage(error),
                    error);
        }
    }

    private static synchronized int acquire() throws IOException {
        if (sDisplayId > Display.DEFAULT_DISPLAY
                && ConsoleDisplayController.displayExists(sDisplayId)) {
            return sDisplayId;
        }
        sDisplayId = Display.INVALID_DISPLAY;
        closeStaleLease();

        final int existingDisplayId =
                ConsoleDisplayController.findOverlayDisplayId();
        if (existingDisplayId > Display.DEFAULT_DISPLAY) {
            sDisplayId = existingDisplayId;
            return existingDisplayId;
        }

        final SimulatedDisplayLease lease = SimulatedDisplayLease.open();
        final long deadline = SystemClock.uptimeMillis()
                + ConsoleDisplayController.START_TIMEOUT_MS;
        do {
            final int createdDisplayId =
                    ConsoleDisplayController.findOverlayDisplayId();
            if (createdDisplayId > Display.DEFAULT_DISPLAY) {
                sLease = lease;
                sDisplayId = createdDisplayId;
                return createdDisplayId;
            }
            SystemClock.sleep(ConsoleDisplayController.STATE_POLL_MS);
        } while (SystemClock.uptimeMillis() < deadline);

        try {
            lease.close();
        } catch (IOException closeError) {
            Log.w(TAG, "Could not restore the overlay display setting",
                    closeError);
        }
        throw new IOException("Android did not create "
                + SimulatedDisplayLease.SPEC);
    }

    private static void closeStaleLease() {
        if (sLease == null) {
            return;
        }
        try {
            sLease.close();
        } catch (IOException error) {
            Log.w(TAG, "Could not close a stale simulated display lease",
                    error);
        }
        sLease = null;
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
