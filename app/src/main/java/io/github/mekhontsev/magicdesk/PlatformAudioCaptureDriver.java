package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.io.IOException;

/** Optional internal-audio backend used alongside Android screenrecord. */
public interface PlatformAudioCaptureDriver {
    enum Availability {
        DECLARED,
        MISSING,
        UNKNOWN,
        UNSUPPORTED
    }

    interface Recorder extends AutoCloseable {
        void start() throws IOException;

        void stop();

        @Override
        void close();
    }

    Availability availability();

    default boolean isAvailable() {
        return availability() == Availability.DECLARED;
    }

    String capabilityDescription();

    Recorder createRecorder(Context context, String outputPath)
            throws IOException;
}
