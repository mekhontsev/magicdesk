package io.github.mekhontsev.magicdesk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Privacy-gated automation adapter for the shared clipboard subsystem. */
final class ClipboardAutomationGateway {
    private static final int MAX_TEXT_CHARS = 262_144;

    private final Context mContext;
    private final AndroidClipboardGateway mClipboard;

    ClipboardAutomationGateway(final Context context) {
        final Context applicationContext =
                context.getApplicationContext();
        mContext = applicationContext == null ? context : applicationContext;
        mClipboard = AndroidClipboardGateway.get(mContext);
    }

    DesktopAutomationResult readText() throws JSONException {
        final AndroidClipboardGateway.TextReadResult read =
                mClipboard.readText();
        final JSONObject observation = metadataJson(read.metadata);
        if (read.metadata.access == AndroidClipboardGateway.Access.DENIED) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.PERMISSION_REQUIRED,
                    "Android denied clipboard access; focus a MagicDesk window and retry",
                    true,
                    observation);
        }
        if (read.metadata.access
                == AndroidClipboardGateway.Access.UNAVAILABLE
                || read.metadata.access == AndroidClipboardGateway.Access.FAILED) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.CLIPBOARD_ACCESS_FAILED,
                    read.metadata.error.isEmpty()
                            ? "clipboard is unavailable"
                            : read.metadata.error,
                    true,
                    observation);
        }
        final boolean truncated = read.text.length() > MAX_TEXT_CHARS;
        final String returnedText = truncated
                ? read.text.substring(0, MAX_TEXT_CHARS) : read.text;
        return DesktopAutomationResult.success(
                read.metadata.access == AndroidClipboardGateway.Access.EMPTY
                        ? "clipboard is empty" : "clipboard text read",
                observation
                        .put("text", returnedText)
                        .put("textLength", read.text.length())
                        .put("truncated", truncated));
    }

    DesktopAutomationResult writeText(final JSONObject args)
            throws JSONException {
        if (args == null || !args.has("text") || args.isNull("text")
                || !(args.opt("text") instanceof String)) {
            throw new IllegalArgumentException("text is required");
        }
        final String text = args.getString("text");
        if (text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException(
                    "text exceeds " + MAX_TEXT_CHARS + " characters");
        }
        final String label = args.optString("label", "MagicDesk automation")
                .trim();
        final AndroidClipboardGateway.OperationResult written =
                mClipboard.writeText(
                        label.isEmpty() ? "MagicDesk automation" : label,
                        text,
                        args.optBoolean("sensitive", false));
        if (!written.successful) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.CLIPBOARD_ACCESS_FAILED,
                    written.error.isEmpty()
                            ? "could not write clipboard" : written.error,
                    true);
        }
        return DesktopAutomationResult.success(
                "clipboard text written",
                metadataJson(written.metadata)
                        .put("textLength", text.length()));
    }

    DesktopAutomationResult clear() throws JSONException {
        final AndroidClipboardGateway.OperationResult cleared =
                FileClipboardInterop.clear(mContext);
        if (!cleared.successful) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.CLIPBOARD_ACCESS_FAILED,
                    cleared.error.isEmpty()
                            ? "could not clear clipboard" : cleared.error,
                    true);
        }
        return DesktopAutomationResult.success(
                "clipboard cleared", metadataJson(cleared.metadata));
    }

    private static JSONObject metadataJson(
            final AndroidClipboardGateway.Metadata metadata)
            throws JSONException {
        final JSONArray mimeTypes = new JSONArray();
        for (final String mimeType : metadata.mimeTypes) {
            mimeTypes.put(mimeType);
        }
        return new JSONObject()
                .put("access", metadata.access.wireName)
                .put("itemCount", metadata.itemCount)
                .put("mimeTypes", mimeTypes)
                .put("sensitive", metadata.sensitive)
                .put("magicDeskFileClip", metadata.fileGeneration >= 0L)
                .put("error", metadata.error);
    }
}
