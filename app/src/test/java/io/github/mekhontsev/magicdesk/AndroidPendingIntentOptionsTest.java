package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import android.app.ActivityOptions;

import org.junit.Test;

public final class AndroidPendingIntentOptionsTest {
    @SuppressWarnings("deprecation")
    @Test
    public void api35UsesTheLegacyAllowedMode() {
        assertEquals(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                AndroidPendingIntentOptions.modeForSdk(35, false));
        assertEquals(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                AndroidPendingIntentOptions.modeForSdk(35, true));
    }

    @Test
    public void api36DistinguishesAlwaysFromVisibleSender() {
        assertEquals(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
                AndroidPendingIntentOptions.modeForSdk(36, false));
        assertEquals(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE,
                AndroidPendingIntentOptions.modeForSdk(36, true));
    }
}
