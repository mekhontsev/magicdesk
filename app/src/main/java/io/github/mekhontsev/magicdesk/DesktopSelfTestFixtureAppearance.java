package io.github.mekhontsev.magicdesk;

import android.content.Intent;

/** Stable visual identity for self-test windows that may be visible together. */
enum DesktopSelfTestFixtureAppearance {
    PRIMARY(0xFF8A2424),
    SECONDARY(0xFF176B3A),
    TRANSITION(0xFF1C4F91);

    private static final String EXTRA_NAME =
            "self_test_fixture_appearance";

    private final int mColor;

    DesktopSelfTestFixtureAppearance(final int color) {
        mColor = color;
    }

    int color() {
        return mColor;
    }

    void putInto(final Intent intent) {
        intent.putExtra(EXTRA_NAME, name());
    }

    static DesktopSelfTestFixtureAppearance from(final Intent intent) {
        if (intent == null) {
            return PRIMARY;
        }
        final String name = intent.getStringExtra(EXTRA_NAME);
        if (name != null) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // Older or malformed debug intents retain the primary color.
            }
        }
        return PRIMARY;
    }
}
