package io.github.mekhontsev.magicdesk;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;

/** Output adapter shared by MCP and the generated CLI, independent of Desktop. */
final class DesktopAutomationPtyOutput {
    private DesktopAutomationPtyOutput() { }

    static DesktopAutomationResult emit(Context context, JSONObject args, boolean tmux) {
        final byte[] bytes;
        final String id;
        final TerminalOutputTarget destination;
        try {
            bytes = PtyPeerOutput.payload(args.has("text") ? args.getString("text") : null,
                    args.has("dataBase64") ? args.getString("dataBase64") : null);
            id = args.getString(tmux ? "target" : "terminalId");
            if (id.isEmpty()) throw new IllegalArgumentException("output target is required");
        } catch (IllegalArgumentException | JSONException error) {
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    ShellAccess.usefulMessage(error), false);
        }
        try {
            destination = TerminalOutputTarget.resolve(tmux ? null : id, tmux ? id : null);
            if (destination.requiresTermux()) {
                final var endpoint = TermuxIntegration.inspect(context);
                if (!endpoint.available()) return DesktopAutomationResult.failure(endpoint.permissionRequired
                                ? DesktopAutomationErrorCode.PERMISSION_REQUIRED : DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                        endpoint.packageName + ": " + endpoint.error, false);
            }
        } catch (IllegalArgumentException error) {
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    ShellAccess.usefulMessage(error), false);
        } catch (IOException error) {
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.CONSOLE_ACCESS_FAILED,
                    ShellAccess.usefulMessage(error), false);
        }
        final JSONObject observation = new JSONObject();
        try {
            observation.put(tmux ? "target" : "terminalId", id).put("bytesRequested", bytes.length);
            final var receipt = destination.write(context, bytes);
            return result(receipt, observation);
        } catch (IOException | RuntimeException error) {
            return DesktopAutomationResult.outcomeUnknown(ShellAccess.usefulMessage(error), false, observation);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    static DesktopAutomationResult result(PtyPeerOutput.Receipt receipt, JSONObject data) throws JSONException {
        data.put("bytesWritten", receipt.bytesWritten()).put("errno", receipt.errno());
        if (receipt.errno() == 0) return DesktopAutomationResult.success("PTY output written", data);
        data.put("safeToRetry", false).put("operationMayContinue", false);
        return DesktopAutomationResult.failure(DesktopAutomationErrorCode.CONSOLE_ACCESS_FAILED,
                "PTY output stopped after " + receipt.bytesWritten() + " bytes (errno " + receipt.errno()
                        + "); do not automatically replay the payload", false, data);
    }
}
