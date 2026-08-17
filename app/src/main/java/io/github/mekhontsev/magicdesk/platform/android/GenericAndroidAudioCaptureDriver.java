package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.PlatformAudioCaptureDriver;

import android.content.Context;

import java.io.IOException;

/** Standard Android exposes no privileged internal-audio recording backend. */
final class GenericAndroidAudioCaptureDriver
        implements PlatformAudioCaptureDriver {
    @Override
    public Availability availability() {
        return Availability.UNSUPPORTED;
    }

    @Override
    public String capabilityDescription() {
        return "not provided by the selected platform";
    }

    @Override
    public Recorder createRecorder(
            final Context context,
            final String outputPath) throws IOException {
        throw new IOException(capabilityDescription());
    }
}
