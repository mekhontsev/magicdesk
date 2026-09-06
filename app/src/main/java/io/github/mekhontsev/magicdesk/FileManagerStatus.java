package io.github.mekhontsev.magicdesk;

/** Listing summaries must not erase the result of an explicit file operation. */
final class FileManagerStatus {
    private String mSummary = "";
    private String mMessage = "";

    void summary(final String text) {
        mSummary = text == null ? "" : text;
    }

    void message(final String text) {
        mMessage = text == null ? "" : text;
    }

    void clearMessage() {
        mMessage = "";
    }

    String text() {
        return mMessage.isEmpty() ? mSummary : mMessage;
    }
}
