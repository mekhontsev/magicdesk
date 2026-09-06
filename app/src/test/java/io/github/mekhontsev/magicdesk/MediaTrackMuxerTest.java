package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import android.media.MediaCodec;
import android.media.MediaExtractor;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MediaTrackMuxerTest {
    @Test
    public void completeBufferIsAValidSampleButLargerFramesAreRejected() throws IOException {
        MediaTrackMuxer.checkSampleSize(-1, 8);
        MediaTrackMuxer.checkSampleSize(0, 8);
        MediaTrackMuxer.checkSampleSize(8, 8);
        assertThrows(IOException.class, () -> MediaTrackMuxer.checkSampleSize(9, 8));
        assertThrows(IOException.class, () -> MediaTrackMuxer.checkSampleSize(Long.MAX_VALUE, 8));
    }

    @Test
    public void finalizationFailureCannotPublishTheMuxAsSuccessful() throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/MediaTrackMuxer.java"));
        final String mux = source.substring(source.indexOf("static void mux("),
                source.indexOf("private static int findTrack("));
        assertTrue(mux.contains("try (Closeable releaseVideo = video::release)"));
        assertTrue(mux.contains("try (Closeable releaseAudio = audio::release)"));
        assertTrue(mux.contains("try (Closeable releaseMuxer = muxer::release)"));
        assertTrue(mux.contains("muxer.stop();"));
        assertFalse(mux.contains("catch (IllegalStateException ignored)"));
        assertFalse(mux.contains("finally"));
        final String copy = source.substring(source.indexOf("private static void copyTrack("));
        assertTrue(copy.indexOf("checkSampleSize(extractor.getSampleSize(), buffer.capacity())")
                < copy.indexOf("extractor.readSampleData("));
    }

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
