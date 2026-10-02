package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONObject;

final class AutomationGuestEnvironments {
    static DesktopAutomationResult execute(String name, JSONObject args) {
        try {
            JSONObject data = switch (name) {
                case "guest.list" -> new JSONObject().put("environments", new JSONArray(GuestEnvironmentOperations.read(args.optString("library", ""), "list", "--json")));
                case "guest.inspect" -> new JSONObject(GuestEnvironmentOperations.read(args.optString("library", ""), "inspect", args.getString("name")));
                case "guest.start" -> GuestEnvironmentOperations.get().start(args.getJSONArray("arguments"), args.optString("library", ""));
                case "guest.status" -> GuestEnvironmentOperations.get().require(args.getString("operationId"))
                        .observe(args.optLong("afterRevision", -1), args.optInt("timeoutMillis", 0));
                case "guest.cancel" -> {
                    var operation = GuestEnvironmentOperations.get().require(args.getString("operationId"));
                    operation.cancel(); yield operation.snapshot();
                }
                default -> throw new IllegalArgumentException("Unknown guest operation");
            };
            return DesktopAutomationResult.success("ok", data);
        } catch (IllegalArgumentException | org.json.JSONException error) {
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.INVALID_ARGUMENT, ShellAccess.usefulMessage(error), false);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.ACTION_FAILED, ShellAccess.usefulMessage(error), false);
        }
    }
    private AutomationGuestEnvironments() { }
}
