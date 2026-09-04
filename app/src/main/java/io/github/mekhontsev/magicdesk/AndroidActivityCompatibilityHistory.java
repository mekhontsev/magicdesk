package io.github.mekhontsev.magicdesk;

import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;

/** Bounded evidence from Activity launches that already happened. */
final class AndroidActivityCompatibilityHistory {
    private static final int MAX_ENTRIES = 64;
    private static final String[] RESULT_FIELDS = {
        "kind", "action", "mimeType", "package", "component",
        "creatorPackage", "activity", "displayId", "mode", "instance",
        "resolvedComponent", "resolution", "handlerCount", "authorization",
        "launchIdentity", "delivery", "relay", "resultExpected",
        "taskObserved", "taskId", "transportTaskId", "reused",
        "observedComponent", "observedTopActivity", "observedActivityType",
        "observedMode", "nativeWindowingMode", "bounds"
    };
    private static final Object LOCK = new Object();
    private static final ArrayDeque<JSONObject> ENTRIES = new ArrayDeque<>();

    private AndroidActivityCompatibilityHistory() {
    }

    static void record(
            final AndroidDesktopAction action,
            final DesktopAutomationResult result) {
        if (action == null || result == null || !launchesActivity(action)) {
            return;
        }
        final JSONObject entry = new JSONObject();
        try {
            entry.put("timestampMillis", System.currentTimeMillis())
                    .put("id", action.id)
                    .put("source", action.source)
                    .put("success", result.success)
                    .put("errorCode", result.errorCode)
                    .put("retryable", result.retryable)
                    .put("presentation", presentation(action.presentation))
                    .put("result", diagnosticResult(result));
            if (action.request != null) {
                entry.put("intent", describeIntent(action.request.intent));
            } else if (action.pendingIntent != null) {
                entry.put("pendingIntentCreator", value(
                        action.pendingIntent.getCreatorPackage()));
            } else if (action.shortcut != null) {
                entry.put("shortcutPackage",
                                action.shortcut.publisher.packageName)
                        .put("shortcutId", action.shortcut.shortcutId);
            }
        } catch (JSONException ignored) {
            return;
        }
        synchronized (LOCK) {
            ENTRIES.addLast(entry);
            while (ENTRIES.size() > MAX_ENTRIES) {
                ENTRIES.removeFirst();
            }
        }
    }

    static JSONArray snapshot(final int requestedLimit) {
        final int limit = Math.max(1, Math.min(MAX_ENTRIES, requestedLimit));
        final JSONObject[] values;
        synchronized (LOCK) {
            values = ENTRIES.toArray(new JSONObject[0]);
        }
        final JSONArray result = new JSONArray();
        for (int index = Math.max(0, values.length - limit);
                index < values.length;
                index++) {
            try {
                result.put(new JSONObject(values[index].toString()));
            } catch (JSONException ignored) {
            }
        }
        return result;
    }

    static JSONObject diagnosticSummary(
            final AndroidDesktopAction action,
            final DesktopAutomationResult result) {
        final JSONObject summary = new JSONObject();
        try {
            summary.put("id", action == null ? "" : action.id)
                    .put("source", action == null ? "" : action.source)
                    .put("success", result != null && result.success)
                    .put("errorCode", result == null ? "" : result.errorCode)
                    .put("retryable", result != null && result.retryable)
                    .put("result", diagnosticResult(result));
        } catch (JSONException ignored) {
        }
        return summary;
    }

    static String report() {
        final JSONArray entries = snapshot(20);
        if (entries.length() == 0) {
            return "## Android Activity compatibility history\n"
                    + "No Activity launches recorded in this process\n\n";
        }
        final StringBuilder report = new StringBuilder(
                "## Android Activity compatibility history\n");
        for (int index = 0; index < entries.length(); index++) {
            report.append(entries.optJSONObject(index)).append('\n');
        }
        return report.append('\n').toString();
    }

    private static boolean launchesActivity(final AndroidDesktopAction action) {
        if (action.kind == AndroidDesktopAction.Kind.PENDING_INTENT) {
            return action.pendingIntent.isActivity();
        }
        if (action.kind == AndroidDesktopAction.Kind.SHORTCUT) {
            return true;
        }
        return action.request.kind == AndroidIntegrationRequest.Kind.ACTIVITY;
    }

    private static JSONObject describeIntent(final Intent intent)
            throws JSONException {
        return new JSONObject()
                .put("action", value(intent.getAction()))
                .put("scheme", intent.getData() == null
                        ? "" : value(intent.getData().getScheme()))
                .put("mimeType", value(intent.getType()))
                .put("package", value(intent.getPackage()))
                .put("component", intent.getComponent() == null
                        ? "" : intent.getComponent().flattenToShortString());
    }

    private static JSONObject diagnosticResult(
            final DesktopAutomationResult result) throws JSONException {
        final JSONObject sanitized = new JSONObject();
        if (result == null) {
            return sanitized;
        }
        final JSONObject source = result.success
                ? result.data : result.observation;
        for (final String field : RESULT_FIELDS) {
            if (source.has(field)) {
                sanitized.put(field, source.opt(field));
            }
        }
        final String dataUri = source.optString("dataUri", "");
        if (!dataUri.isEmpty()) {
            sanitized.put("dataScheme", value(
                    android.net.Uri.parse(dataUri).getScheme()));
        }
        return sanitized;
    }

    private static JSONObject presentation(
            final DesktopLaunchPresentation value) throws JSONException {
        final JSONObject result = new JSONObject()
                .put("mode", value.mode.wireName)
                .put("instance", value.instancePolicy.wireName)
                .put("preferredTaskId", value.preferredTaskId);
        if (value.bounds != null) {
            result.put("bounds", new JSONObject()
                    .put("x", value.bounds.x)
                    .put("y", value.bounds.y)
                    .put("width", value.bounds.width)
                    .put("height", value.bounds.height));
        }
        return result;
    }

    private static String value(final String value) {
        return value == null ? "" : value;
    }
}
