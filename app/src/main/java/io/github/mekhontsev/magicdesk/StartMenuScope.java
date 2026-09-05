package io.github.mekhontsev.magicdesk;

/** Fixed launch/history/search scope; never inferred from current input focus. */
enum StartMenuScope {
    DESKTOP("recent_packages"),
    PHONE("phone_recent_packages");

    final String historyKey;

    StartMenuScope(final String historyKey) {
        this.historyKey = historyKey;
    }
}
