package io.github.mekhontsev.magicdesk;

import java.util.Locale;

/** Controls how far a self-test proceeds after a diagnostic failure. */
enum DesktopSelfTestExecutionPolicy {
    FULL,
    FAIL_FAST;

    boolean stopsAfterFailure() {
        return this == FAIL_FAST;
    }

    String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    static DesktopSelfTestExecutionPolicy parse(final String value) {
        if (value == null || value.isEmpty()
                || "full".equalsIgnoreCase(value)) {
            return FULL;
        }
        if ("fail_fast".equalsIgnoreCase(value)
                || "fail-fast".equalsIgnoreCase(value)) {
            return FAIL_FAST;
        }
        throw new IllegalArgumentException(
                "self-test mode must be full or fail_fast");
    }
}
