package io.github.mekhontsev.magicdesk;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;

final class MediaTrackMuxer {
    private static final int DEFAULT_BUFFER_BYTES = 8 * 1024 * 1024;

    private MediaTrackMuxer() {
    }

    static void mux(
            final String videoPath,
            final String audioPath,
            final String outputPath,
            final long videoStartedNanos,
            final long audioStartedNanos) throws IOException {
        final MediaExtractor video = new MediaExtractor();
        final MediaExtractor audio = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            video.setDataSource(videoPath);
            audio.setDataSource(audioPath);
            final int videoSourceTrack = findTrack(video, "video/");
            final int audioSourceTrack = findTrack(audio, "audio/");
            if (videoSourceTrack < 0 || audioSourceTrack < 0) {
                throw new IOException("recording is missing a video or audio track");
            }
            video.selectTrack(videoSourceTrack);
            audio.selectTrack(audioSourceTrack);

            muxer = new MediaMuxer(
                    outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            copyOrientationHint(videoPath, muxer);
            final int videoTargetTrack = muxer.addTrack(
                    video.getTrackFormat(videoSourceTrack));
            final int audioTargetTrack = muxer.addTrack(
                    audio.getTrackFormat(audioSourceTrack));
            muxer.start();

            final long originNanos = Math.min(
                    videoStartedNanos, audioStartedNanos);
            copyTrack(
                    video,
                    muxer,
                    videoTargetTrack,
                    Math.max(0L, (videoStartedNanos - originNanos) / 1_000L));
            copyTrack(
                    audio,
                    muxer,
                    audioTargetTrack,
                    Math.max(0L, (audioStartedNanos - originNanos) / 1_000L));
        } finally {
            video.release();
            audio.release();
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (IllegalStateException ignored) {
                    // The muxer may not have started after an earlier failure.
                }
                muxer.release();
            }
        }
    }

    private static int findTrack(
            final MediaExtractor extractor,
            final String mimePrefix) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            final String mime = extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                return index;
            }
        }
        return -1;
    }

    private static void copyTrack(
            final MediaExtractor extractor,
            final MediaMuxer muxer,
            final int targetTrack,
            final long trackOffsetUs) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_BYTES);
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        final long firstSampleUs = extractor.getSampleTime();
        if (firstSampleUs < 0) {
            throw new IOException("recording track contains no samples");
        }
        while (true) {
            buffer.clear();
            final int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }
            if (size == buffer.capacity()) {
                throw new IOException("encoded recording sample is too large");
            }
            info.set(
                    0,
                    size,
                    Math.max(0L, extractor.getSampleTime() - firstSampleUs)
                            + trackOffsetUs,
                    codecBufferFlags(extractor.getSampleFlags()));
            buffer.position(0);
            buffer.limit(size);
            muxer.writeSampleData(targetTrack, buffer, info);
            if (!extractor.advance()) {
                break;
            }
        }
    }

    static int codecBufferFlags(final int extractorFlags)
            throws IOException {
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
            throw new IOException("encrypted recording samples are unsupported");
        }
        int codecFlags = 0;
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            codecFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            codecFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
        }
        return codecFlags;
    }

    private static void copyOrientationHint(
            final String videoPath,
            final MediaMuxer muxer) {
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoPath);
            final String rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation != null) {
                muxer.setOrientationHint(Integer.parseInt(rotation));
            }
        } catch (RuntimeException ignored) {
            // Rotation metadata is optional; the encoded dimensions remain usable.
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
                // No writable resource depends on metadata cleanup succeeding.
            }
        }
    }
}
