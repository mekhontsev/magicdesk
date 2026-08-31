package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import org.json.JSONException;
import org.json.JSONObject;

/** Shell-identity discovery provider that is not restricted by app package visibility. */
final class ShellAndroidIntegrationGateway {
    private ShellAndroidIntegrationGateway() {
    }

    static String queryHandlers(
            final Context context,
            final String requestJson) {
        try {
            final JSONObject args = requestJson == null
                    || requestJson.trim().isEmpty()
                    ? new JSONObject() : new JSONObject(requestJson);
            final AndroidIntegrationRequest request =
                    AndroidIntegrationRequest.parse(
                            args, AndroidIntegrationRequest.Kind.ACTIVITY);
            final PackageManager packageManager = ShellIdentityContext.create(context)
                    .getPackageManager();
            return new JSONObject()
                    .put("success", true)
                    .put("message", "Android handlers resolved")
                    .put("data", AndroidIntentHandlerQuery.query(
                            packageManager,
                            request,
                            args.optInt("limit", 100),
                            "shell"))
                    .toString();
        } catch (JSONException | PackageManager.NameNotFoundException
                | RuntimeException error) {
            try {
                return new JSONObject()
                        .put("success", false)
                        .put("message", ShellAccess.usefulMessage(error))
                        .put("data", new JSONObject())
                        .toString();
            } catch (JSONException impossible) {
                return "{\"success\":false,\"message\":\"serialization failed\"}";
            }
        }
    }

    static AndroidActivityResolution resolveActivity(
            final Context context,
            final Intent intent) throws PackageManager.NameNotFoundException {
        if (intent == null) {
            throw new IllegalArgumentException("Intent is required");
        }
        return AndroidActivityResolution.resolve(
                ShellIdentityContext.create(context).getPackageManager(), intent);
    }

}
