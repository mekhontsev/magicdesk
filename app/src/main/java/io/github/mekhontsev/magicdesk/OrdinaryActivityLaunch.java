package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** Dispatches already-authorized Activities without acquiring a Desktop session. */
final class OrdinaryActivityLaunch {
    private OrdinaryActivityLaunch() { }

    static boolean canLaunchWindowed() {
        return ShellAccess.isReady() && PlatformDrivers.current().windowing().supportsIndependentFreeform();
    }

    static void requirePresentation(final DesktopLaunchPresentation presentation) {
        if (presentation.preferredTaskId != -1) {
            throw new IllegalArgumentException("preferredTaskId requires Desktop placement");
        }
        if (presentation.mode == DesktopLaunchMode.WINDOWED && !canLaunchWindowed()) {
            throw new IllegalStateException("Independent windowed launch requires Shell access "
                    + "and firmware that supports freeform windows without managed Desktop");
        }
    }

    static void launch(final Context context, final Intent intent,
            final AndroidLaunchSpec.Delivery delivery, final int displayId) throws IOException {
        launch(context, intent, delivery, displayId, DesktopLaunchPresentation.automatic());
    }

    static Rect windowBounds(final int displayId, final DesktopLaunchPresentation presentation)
            throws IOException {
        requirePresentation(presentation);
        return presentation.mode == DesktopLaunchMode.WINDOWED
                ? FloatingWindowController.getWindowBounds(displayId, presentation.bounds) : null;
    }

    static void launch(final Context context, final Intent intent,
            final AndroidLaunchSpec.Delivery delivery, final int displayId,
            final DesktopLaunchPresentation presentation) throws IOException {
        final Rect bounds = windowBounds(displayId, presentation);
        ApplicationTaskPlacement.prepareIndependentLaunch(context, intent, displayId, presentation);
        if (delivery == AndroidLaunchSpec.Delivery.APP_PENDING_INTENT) {
            final PendingIntent token = AndroidPendingActivityLaunch.create(context, intent);
            try {
                ShellAccess.sendActivityOnDisplay(token, displayId, bounds);
            } finally {
                // The token is one-shot; never retain an unused launch capability.
                token.cancel();
            }
        } else {
            ShellAccess.launchActivityOnDisplay(intent, displayId, bounds == null, bounds);
        }
    }

    static void send(final PendingIntent token, final int displayId,
            final DesktopLaunchPresentation presentation) throws IOException {
        // Published shortcuts and notification tokens remain owned by their creator.
        ShellAccess.sendActivityOnDisplay(token, displayId, windowBounds(displayId, presentation));
    }

    static DesktopAutomationResult accepted(final int displayId,
            final DesktopLaunchPresentation presentation, final JSONObject data)
            throws JSONException {
        // A successful dispatch does not establish task identity or visible completion.
        return DesktopAutomationResult.success("Android Activity launch accepted", data
                .put("accepted", true)
                .put("placement", "display")
                .put("displayId", displayId)
                .put("mode", presentation.mode == DesktopLaunchMode.WINDOWED ? "windowed" : "fullscreen")
                .put("taskObserved", false)
                .put("nextAction", "Use ui.wait on this display to verify the requested interface."));
    }
}
