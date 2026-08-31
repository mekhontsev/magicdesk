package io.github.mekhontsev.magicdesk;

import org.json.JSONException;

import java.io.IOException;

/** Executes explicit clipboard-to-Intent actions through the desktop launcher. */
final class AndroidContentActionGateway {
    private static final Object DIAGNOSTICS_LOCK = new Object();
    private static long sOpenRequests;
    private static long sShareRequests;
    private static long sFailures;
    private static String sLastOperation = "none";

    private final AndroidClipboardGateway mClipboard;
    private final AndroidIntegrationGateway mAndroid;

    AndroidContentActionGateway(final android.content.Context context) {
        this(context, new AndroidIntegrationGateway(context));
    }

    AndroidContentActionGateway(
            final android.content.Context context,
            final AndroidIntegrationGateway android) {
        mClipboard = AndroidClipboardGateway.get(context);
        mAndroid = android;
    }

    DesktopAutomationResult openClipboard(final int displayId) {
        return execute(true, displayId);
    }

    DesktopAutomationResult shareClipboard(final int displayId) {
        return execute(false, displayId);
    }

    static String runtimeDiagnostics() {
        synchronized (DIAGNOSTICS_LOCK) {
            return "opens=" + sOpenRequests
                    + ", shares=" + sShareRequests
                    + ", failures=" + sFailures
                    + ", lastOperation=" + sLastOperation;
        }
    }

    private DesktopAutomationResult execute(
            final boolean open,
            final int displayId) {
        final String operation = open ? "open" : "share";
        recordRequest(operation, open);
        final AndroidClipboardGateway.ContentReadResult read =
                mClipboard.readContent();
        if (read.content == null) {
            recordFailure(operation);
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.CLIPBOARD_ACCESS_FAILED,
                    read.metadata == null || read.metadata.error.isEmpty()
                            ? "clipboard is empty or unavailable"
                            : read.metadata.error,
                    read.metadata != null
                            && read.metadata.access
                                    == AndroidClipboardGateway.Access.DENIED);
        }
        if (open && !read.content.canOpen()) {
            recordFailure(operation);
            return DesktopAutomationResult.failure(
                    "clipboard content has no single file or web link to open");
        }
        if (!open && !read.content.canShare()) {
            recordFailure(operation);
            return DesktopAutomationResult.failure(
                    "clipboard content is empty");
        }
        try {
            final DesktopAutomationResult result = open
                    ? mAndroid.openContent(read.content, displayId)
                    : mAndroid.shareContent(read.content, displayId);
            if (!result.success) {
                recordFailure(operation);
            }
            return result;
        } catch (IOException | JSONException | RuntimeException error) {
            recordFailure(operation);
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    ShellAccess.usefulMessage(error),
                    false);
        }
    }

    private static void recordRequest(
            final String operation,
            final boolean open) {
        synchronized (DIAGNOSTICS_LOCK) {
            if (open) {
                sOpenRequests++;
            } else {
                sShareRequests++;
            }
            sLastOperation = operation;
        }
    }

    private static void recordFailure(final String operation) {
        synchronized (DIAGNOSTICS_LOCK) {
            sFailures++;
            sLastOperation = operation + "_failed";
        }
    }
}
