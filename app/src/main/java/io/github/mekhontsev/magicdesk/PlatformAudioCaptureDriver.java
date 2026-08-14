package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.io.IOException;

/** Optional internal-audio backend used alongside Android screenrecord. */
public interface PlatformAudioCaptureDriver {
    interface Recorder extends AutoCloseable {
        void start() throws IOException;

        void stop();

        @Override
        void close();
    }

    boolean isAvailable();

    String capabilityDescription();

    Recorder createRecorder(Context context, String outputPath)
            throws IOException;
}
