package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.mekhontsev.magicdesk.PlatformAudioCaptureDriver;

import org.junit.Test;

public final class InternalAudioSourceCapabilityTest {
    @Test
    public void recognizesDeclaredVendorSourceWithoutRecording() {
        final InternalAudioSourceCapability.Result result =
                InternalAudioSourceCapability.inspect(DeclaredSource.class);

        assertEquals(
                PlatformAudioCaptureDriver.Availability.DECLARED,
                result.availability);
        assertTrue(result.description.contains("source=80"));
        assertTrue(result.description.contains("SYSTEM_RECORD_MODE"));
    }

    @Test
    public void reportsMissingVendorSource() {
        final InternalAudioSourceCapability.Result result =
                InternalAudioSourceCapability.inspect(MissingSource.class);

        assertEquals(
                PlatformAudioCaptureDriver.Availability.MISSING,
                result.availability);
    }

    @Test
    public void preservesUnknownWhenFrameworkCannotBeInspected() {
        final InternalAudioSourceCapability.Result result =
                InternalAudioSourceCapability.inspect(NoProbeApi.class);

        assertEquals(
                PlatformAudioCaptureDriver.Availability.UNKNOWN,
                result.availability);
    }

    public static final class DeclaredSource {
        public static boolean isValidAudioSource(final int source) {
            return source == InternalAudioSourceCapability.SOURCE;
        }

        public static String toLogFriendlyAudioSource(final int source) {
            return source == InternalAudioSourceCapability.SOURCE
                    ? "SYSTEM_RECORD_MODE" : "unknown";
        }
    }

    public static final class MissingSource {
        public static boolean isValidAudioSource(final int source) {
            return false;
        }
    }

    public static final class NoProbeApi {
    }
}
