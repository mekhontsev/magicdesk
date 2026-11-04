package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;

/** Authorized adapter; ControlActivity owns the foreground mode and its lifecycle. */
final class AutomationBlackScreen {
    static DesktopAutomationResult execute(JSONObject args) throws org.json.JSONException {
        boolean enabled = args.getBoolean("enabled");
        var request = new java.util.concurrent.CompletableFuture<Boolean>();
        AutomationMainThread.submit(() -> {
            ControlActivity.requestBlackScreen(enabled, request);
            return null;
        }).whenComplete((unused, error) -> {
            if (error != null) request.completeExceptionally(error);
        });
        var pending = request.thenApply(active -> {
            try { return new JSONObject().put("enabled", active).put("displayId", 0); }
            catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        });
        // EVENT_WAIT: focused ControlActivity applied the window policy; expiry revokes a pending activation.
        var result = AutomationMainThread.await(pending, "phone black-screen mode", false);
        if (!result.success) request.cancel(false);
        return result;
    }
    private AutomationBlackScreen() { }
}
