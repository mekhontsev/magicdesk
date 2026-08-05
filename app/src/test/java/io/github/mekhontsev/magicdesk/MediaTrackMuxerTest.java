package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.media.MediaCodec;
import android.media.MediaExtractor;

import org.junit.Test;

import java.io.IOException;

public final class MediaTrackMuxerTest {
    @Test
    public void mapsExtractorFlagsToCodecFlags() throws IOException {
        final int extractorFlags = MediaExtractor.SAMPLE_FLAG_SYNC
                | MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME;

        assertEquals(
                MediaCodec.BUFFER_FLAG_KEY_FRAME
                        | MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
                MediaTrackMuxer.codecBufferFlags(extractorFlags));
    }

    @Test
    public void rejectsEncryptedSamples() {
        assertThrows(
                IOException.class,
                () -> MediaTrackMuxer.codecBufferFlags(
                        MediaExtractor.SAMPLE_FLAG_ENCRYPTED));
    }
}
