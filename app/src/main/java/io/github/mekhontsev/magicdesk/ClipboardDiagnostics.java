package io.github.mekhontsev.magicdesk;

/** Metadata-only diagnostics for system and file clipboard domains. */
final class ClipboardDiagnostics {
    private ClipboardDiagnostics() {
    }

    static String describe() {
        return "android={" + AndroidClipboardGateway.runtimeDiagnostics()
                + "}, files={" + FileOperationClipboard.diagnostics() + "}";
    }
}
