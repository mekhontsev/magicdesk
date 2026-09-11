package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.io.IOException;

/** Owns the optional Android display with a phone preview. */
final class SimulatedDesktopDisplayController {
    private static final String TAG = "MagicDeskSimulatedDisplay";

    private static SimulatedDisplayLease sLease;
    private static String sUniqueId;
    private static int sDisplayId = Display.INVALID_DISPLAY;

    private SimulatedDesktopDisplayController() {
    }

    static void show() {
        int displayId = Display.INVALID_DISPLAY;
        try {
            displayId = acquire();
            DesktopDisplayDrivers
                    .forKind(DesktopDisplayOutput.Kind.SIMULATED)
                    .showReady(
                            null,
                            DesktopDisplayTarget.simulated(displayId));
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Could not open the simulated desktop", error);
            CompatibilityDiagnostics.record(
                    "DISPLAY-SIMULATED-001",
                    "Could not open the simulated desktop",
                    usefulMessage(error),
                    error);
        }
    }

    static synchronized boolean owns(final DesktopDisplayInfo display) {
        return sLease != null && display.id == sDisplayId && display.uniqueId.equals(sUniqueId);
    }

    static synchronized int create(final VirtualDisplaySpec spec) throws IOException {
        // Android's overlay setting replaces the whole set. Never rewrite it
        // while another overlay exists, including a contributor's test display.
        if (ExternalDisplayController.findOverlayDisplayId() > Display.DEFAULT_DISPLAY) {
            throw new IOException("An overlay display already exists");
        }
        closeStaleLease();
        return acquireNew(spec);
    }

    // The transition coordinator closes any session and verifies quiescence.
    static boolean release(final int displayId) {
        final SimulatedDisplayLease lease;
        synchronized (SimulatedDesktopDisplayController.class) {
            if (sDisplayId != displayId) {
                return true;
            }
            lease = sLease;
        }
        if (lease == null) {
            return true;
        }
        try {
            synchronized (SimulatedDesktopDisplayController.class) {
                if (sDisplayId != displayId || sLease != lease) {
                    return true;
                }
                sDisplayId = Display.INVALID_DISPLAY;
                sUniqueId = null;
                sLease = null;
            }
            lease.close();
            return true;
        } catch (IOException error) {
            Log.w(TAG, "Could not remove the simulated display", error);
            CompatibilityDiagnostics.record(
                    "DISPLAY-SIMULATED-002",
                    "Could not remove the simulated desktop display",
                    usefulMessage(error),
                    error);
            return false;
        }
    }

    private static synchronized int acquire() throws IOException {
        if (sDisplayId > Display.DEFAULT_DISPLAY
                && ExternalDisplayController.displayExists(sDisplayId)) {
            return sDisplayId;
        }
        sDisplayId = Display.INVALID_DISPLAY;
        closeStaleLease();

        final int existingDisplayId =
                ExternalDisplayController.findOverlayDisplayId();
        if (existingDisplayId > Display.DEFAULT_DISPLAY) {
            sDisplayId = existingDisplayId;
            return existingDisplayId;
        }

        return acquireNew(new VirtualDisplaySpec(1920, 1080, 160));
    }

    private static int acquireNew(final VirtualDisplaySpec spec) throws IOException {
        final SimulatedDisplayLease lease = SimulatedDisplayLease.open(spec);
        final long deadline = SystemClock.uptimeMillis()
                + ExternalDisplayController.START_TIMEOUT_MS;
        boolean acquired = false;
        try {
            do {
                final int createdDisplayId = ExternalDisplayController.findOverlayDisplayId();
                if (createdDisplayId > Display.DEFAULT_DISPLAY) {
                    sUniqueId = ExternalDisplayController.getDisplayUniqueId(createdDisplayId);
                    sLease = lease;
                    sDisplayId = createdDisplayId;
                    acquired = true;
                    return createdDisplayId;
                }
                BoundedStateAwaiter.pause(BoundedStateAwaiter.Reason.DISPLAY_STATE,
                        ExternalDisplayController.STATE_POLL_MS);
            } while (SystemClock.uptimeMillis() < deadline);
            throw new IOException("Android did not create the requested preview display");
        } finally {
            if (!acquired) { lease.close(); }
        }
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
