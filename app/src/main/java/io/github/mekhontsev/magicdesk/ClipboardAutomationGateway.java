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

    DesktopAutomationResult readText(JSONObject args) throws JSONException, java.io.IOException, InterruptedException {
        if (!args.has("expectedText")) {
            if (args.has("timeoutMillis")) throw new IllegalArgumentException("timeoutMillis requires expectedText");
            return describeText(mClipboard.readText());
        }
        if (!(args.opt("expectedText") instanceof String expected) || expected.length() > MAX_TEXT_CHARS)
            throw new IllegalArgumentException("expectedText must be bounded plain text");
        int timeout = args.has("timeoutMillis") ? AutomationJsonArguments.requiredInt(args, "timeoutMillis") : 5000;
        if (timeout < 0 || timeout > 30_000) throw new IllegalArgumentException("timeoutMillis must be 0 to 30000");
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Clipboard observation must not block the main thread");
        return awaitText(mClipboard::readText, mClipboard::observe, expected, timeout);
    }

    static DesktopAutomationResult awaitText(
            java.util.function.Supplier<AndroidClipboardGateway.TextReadResult> read,
            java.util.function.Function<Runnable, AutoCloseable> observe,
            String expected, int timeoutMillis) throws JSONException, java.io.IOException, InterruptedException {
        Object gate = new Object();
        long[] revision = {0};
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        AutoCloseable listener = observe.apply(() -> {
            synchronized (gate) { revision[0]++; gate.notifyAll(); }
        });
        try {
            for (;;) {
                long before;
                synchronized (gate) { before = revision[0]; }
                var result = describeText(read.get());
                if (!result.success) return result;
                boolean matched = !result.data.getBoolean("truncated") && expected.equals(result.data.getString("text"));
                result.data.put("matched", matched);
                long remaining = deadline - System.nanoTime();
                if (matched || remaining <= 0) return result;
                synchronized (gate) {
                    if (before != revision[0]) continue;
                    // EVENT_WAIT: Android primary-clip change; expiry returns matched=false, not copy completion.
                    EventDrivenWaits.await(gate, EventDrivenWaits.Reason.CLIPBOARD_CHANGE,
                            Math.max(1, (remaining + 999_999) / 1_000_000));
                }
            }
        } finally {
            try { listener.close(); }
            catch (Exception error) { throw new java.io.IOException("Could not release clipboard observation", error); }
        }
    }

    static DesktopAutomationResult describeText(
            final AndroidClipboardGateway.TextReadResult read) throws JSONException {
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
        final String returnedText = BoundedText.prefix(read.text, MAX_TEXT_CHARS);
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
