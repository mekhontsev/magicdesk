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

    boolean matchesRenderedColor(final int actual) {
        if ((actual >>> 24) != 0xFF) {
            return false;
        }
        // A composed screenshot includes neutral task shadows. Identify the
        // fixture by its RGB proportions, not its uncomposited brightness.
        double dot = 0;
        double squaredLength = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            final int expectedChannel = (mColor >>> shift) & 0xFF;
            dot += expectedChannel * ((actual >>> shift) & 0xFF);
            squaredLength += expectedChannel * expectedChannel;
        }
        final double intensity = Math.min(1.0, dot / squaredLength);
        // Do not accept a nearly black or obscured window as a visible fixture.
        if (intensity < 0.5) {
            return false;
        }
        for (int shift = 0; shift <= 16; shift += 8) {
            final double expectedChannel = ((mColor >>> shift) & 0xFF) * intensity;
            if (Math.abs(expectedChannel - ((actual >>> shift) & 0xFF)) > 3) {
                return false;
            }
        }
        return true;
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
                // Malformed debug intents retain the primary color.
            }
        }
        return PRIMARY;
    }
}
