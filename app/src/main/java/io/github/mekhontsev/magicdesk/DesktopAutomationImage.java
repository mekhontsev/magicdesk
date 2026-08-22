package io.github.mekhontsev.magicdesk;

/** In-memory image attachment returned by an automation transport. */
final class DesktopAutomationImage {
    final String mimeType;
    final String base64Data;

    DesktopAutomationImage(final String mimeType, final String base64Data) {
        this.mimeType = mimeType;
        this.base64Data = base64Data;
    }
}
