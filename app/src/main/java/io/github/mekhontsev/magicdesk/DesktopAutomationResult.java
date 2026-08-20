package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

/** Typed outcome shared by MCP and Android App Functions adapters. */
final class DesktopAutomationResult {
    final boolean success;
    final String message;
    final JSONObject data;

    private DesktopAutomationResult(
            final boolean success,
            final String message,
            final JSONObject data) {
        this.success = success;
        this.message = message == null ? "" : message;
        this.data = data == null ? new JSONObject() : data;
    }

    static DesktopAutomationResult success(
            final String message, final JSONObject data) {
        return new DesktopAutomationResult(true, message, data);
    }

    static DesktopAutomationResult failure(final String message) {
        return new DesktopAutomationResult(false, message, null);
    }

    static DesktopAutomationResult failure(
            final String message, final JSONObject data) {
        return new DesktopAutomationResult(false, message, data);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("success", success)
                .put("message", message)
                .put("data", data);
    }
}
