package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

/** Typed outcome shared by MCP and Android App Functions adapters. */
final class DesktopAutomationResult {
    final boolean success;
    final String message;
    final JSONObject data;
    final String errorCode;
    final boolean retryable;
    final JSONObject observation;
    final DesktopAutomationImage image;

    private DesktopAutomationResult(
            final boolean success,
            final String message,
            final JSONObject data,
            final String errorCode,
            final boolean retryable,
            final JSONObject observation,
            final DesktopAutomationImage image) {
        this.success = success;
        this.message = message == null ? "" : message;
        this.data = data == null ? new JSONObject() : data;
        this.errorCode = errorCode == null ? "" : errorCode;
        this.retryable = retryable;
        this.observation = observation == null
                ? new JSONObject() : observation;
        this.image = image;
    }

    static DesktopAutomationResult success(
            final String message, final JSONObject data) {
        return new DesktopAutomationResult(
                true, message, data, "", false, null, null);
    }

    static DesktopAutomationResult success(
            final String message,
            final JSONObject data,
            final DesktopAutomationImage image) {
        return new DesktopAutomationResult(
                true, message, data, "", false, null, image);
    }

    static DesktopAutomationResult failure(final String message) {
        return failure(
                DesktopAutomationErrorCode.ACTION_FAILED,
                message,
                false,
                null);
    }

    static DesktopAutomationResult outcomeUnknown(final String message,
            final boolean safeToRetry, final JSONObject observation) {
        try {
            final JSONObject state = observation == null ? new JSONObject()
                    : new JSONObject(observation.toString());
            state.put("completionConfirmed", false).put("operationMayContinue", true)
                    .put("safeToRetry", safeToRetry);
            return failure(DesktopAutomationErrorCode.OUTCOME_UNKNOWN,
                    message + "; completion is unconfirmed and the operation was not cancelled. "
                            + (safeToRetry ? "Retry only the identical request."
                                    : "Observe the resulting state before issuing another action."),
                    safeToRetry, state);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static DesktopAutomationResult failure(
            final String message, final JSONObject data) {
        return new DesktopAutomationResult(
                false,
                message,
                data,
                DesktopAutomationErrorCode.ACTION_FAILED,
                false,
                data,
                null);
    }

    static DesktopAutomationResult failure(
            final String errorCode,
            final String message,
            final boolean retryable) {
        return failure(errorCode, message, retryable, null);
    }

    static DesktopAutomationResult failure(
            final String errorCode,
            final String message,
            final boolean retryable,
            final JSONObject observation) {
        return new DesktopAutomationResult(
                false,
                message,
                null,
                errorCode,
                retryable,
                observation,
                null);
    }

    JSONObject toJson() throws JSONException {
        final JSONObject result = new JSONObject()
                .put("success", success)
                .put("message", message)
                .put("data", data);
        if (success) {
            return result.put("error", JSONObject.NULL);
        }
        return result.put("error", new JSONObject()
                .put("code", errorCode)
                .put("retryable", retryable)
                .put("observation", observation));
    }
}
