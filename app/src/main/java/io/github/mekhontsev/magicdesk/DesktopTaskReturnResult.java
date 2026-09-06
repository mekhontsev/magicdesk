package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

/** Final command result, separate from per-task recovery diagnostics. */
final class DesktopTaskReturnResult {
    private DesktopTaskReturnResult() {
    }

    static String encode(final int displayId, final int returned, final int failed) {
        if (displayId <= 0 || returned < 0 || failed < 0) {
            throw new IllegalArgumentException("invalid task return result");
        }
        try {
            return new JSONObject()
                    .put("format", 1)
                    .put("from", displayId)
                    .put("to", 0)
                    .put("returned", returned)
                    .put("failed", failed)
                    .put("success", failed == 0)
                    .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("could not encode task return result", error);
        }
    }

    static boolean succeeded(final String output, final int displayId) {
        if (output == null || displayId <= 0) {
            return false;
        }
        final String trimmed = output.trim();
        final String summary = trimmed.substring(trimmed.lastIndexOf('\n') + 1);
        try {
            final JSONObject result = new JSONObject(summary);
            return result.getInt("format") == 1
                    && result.getInt("from") == displayId
                    && result.getInt("to") == 0
                    && result.getInt("returned") >= 0
                    && result.getInt("failed") == 0
                    && result.getBoolean("success");
        } catch (JSONException error) {
            return false;
        }
    }
}
