package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformAudioCaptureDriver;
import io.github.mekhontsev.magicdesk.MediaRecorderAudioRecorder;

import android.content.Context;

/** Internal-audio backend exposed by compatible Nubia firmware. */
final class NubiaAudioCaptureDriver implements PlatformAudioCaptureDriver {
    @Override
    public Availability availability() {
        return InternalAudioSourceCapability.current().availability;
    }

    @Override
    public String capabilityDescription() {
        return InternalAudioSourceCapability.current().description;
    }

    @Override
    public Recorder createRecorder(
            final Context context,
            final String outputPath) {
        return new MediaRecorderAudioRecorder(
                context, outputPath, InternalAudioSourceCapability.SOURCE);
    }
}
