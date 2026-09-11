package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** Dispatches already-authorized Activities without acquiring a Desktop session. */
final class OrdinaryActivityLaunch {
    private OrdinaryActivityLaunch() { }

    static void requirePresentation(final DesktopLaunchPresentation presentation) {
        if (presentation.mode == DesktopLaunchMode.WINDOWED || presentation.bounds != null
                || presentation.preferredTaskId != -1) {
            throw new IllegalArgumentException(
                    "windowed mode, bounds and preferredTaskId require Desktop placement");
        }
    }

    static void launch(final Context context, final Intent intent,
            final AndroidLaunchSpec.Delivery delivery, final int displayId) throws IOException {
        if (delivery == AndroidLaunchSpec.Delivery.APP_PENDING_INTENT) {
            final PendingIntent token = AndroidPendingActivityLaunch.create(context, intent);
            try {
                ShellAccess.sendActivityOnDisplay(token, displayId);
            } finally {
                // The token is one-shot; never retain an unused launch capability.
                token.cancel();
            }
        } else {
            ShellAccess.launchActivityOnDisplay(intent, displayId, true);
        }
    }

    static DesktopAutomationResult accepted(final int displayId, final JSONObject data)
            throws JSONException {
        // A successful dispatch does not establish task identity or visible completion.
        return DesktopAutomationResult.success("Android Activity launch accepted", data
                .put("accepted", true)
                .put("placement", "display")
                .put("displayId", displayId)
                .put("mode", "fullscreen")
                .put("taskObserved", false)
                .put("nextAction", "Use ui.wait on this display to verify the requested interface."));
    }
}
