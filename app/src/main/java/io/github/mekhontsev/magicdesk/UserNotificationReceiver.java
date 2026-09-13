package io.github.mekhontsev.magicdesk;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONException;
import org.json.JSONObject;

/** Explicit app-private PendingIntents only publish a result; no shell command is stored here. */
public final class UserNotificationReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        final UserInteractions owner = UserInteractions.current();
        if (owner == null) return;
        final String id = intent.getStringExtra(UserInteractions.EXTRA_ID);
        final UserInteractionRequest request = owner.registry.pending(id);
        if (request == null || !request.kind().equals("notification")) return;
        if (intent.getBooleanExtra("dismiss", false)) {
            owner.registry.complete(id, "cancelled", null, "");
            return;
        }
        final String action = intent.getStringExtra(UserInteractions.EXTRA_ACTION);
        final UserInteractionRequest.Item item = request.items().stream()
                .filter(value -> value.id().equals(action)).findFirst().orElse(null);
        if (!"open".equals(action) && item == null) return;
        try {
            final JSONObject result = new JSONObject().put("actionId", action);
            if (item != null && item.reply()) {
                final Bundle reply = RemoteInput.getResultsFromIntent(intent);
                if (reply == null || reply.getCharSequence(UserInteractions.REPLY) == null) return;
                final String text = reply.getCharSequence(UserInteractions.REPLY).toString();
                if (text.length() > 8192) {
                    owner.registry.complete(id, "failed", null, "reply exceeds 8192 characters");
                    return;
                }
                result.put("text", text);
            }
            owner.registry.complete(id, "completed", result, "");
        } catch (JSONException error) {
            owner.registry.complete(id, "failed", null, error.getMessage());
        }
    }
}
