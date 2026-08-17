package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;

final class DisplayRecordingSettings {
    static final int DEFAULT_SCALE_PERCENT = 100;
    static final int DEFAULT_BITRATE_MBPS = 20;
    static final int MIN_BITRATE_MBPS = 4;
    static final int MAX_BITRATE_MBPS = 40;

    private static final String PREFS = "magicdesk_recording";
    private static final String PREF_SCALE_PERCENT = "scale_percent";
    private static final String PREF_BITRATE_MBPS = "bitrate_mbps";
    private static final String PREF_AUDIO_MODE = "audio_mode";

    private DisplayRecordingSettings() {
    }

    static Values load(final Context context) {
        final SharedPreferences preferences = preferences(context);
        return new Values(
                sanitizeScale(preferences.getInt(
                        PREF_SCALE_PERCENT, DEFAULT_SCALE_PERCENT)),
                sanitizeBitrate(preferences.getInt(
                        PREF_BITRATE_MBPS, DEFAULT_BITRATE_MBPS)),
                RecordingAudioMode.fromStoredValue(preferences.getString(
                        PREF_AUDIO_MODE,
                        RecordingAudioMode.AUTO.storedValue())));
    }

    static void saveScale(final Context context, final int scalePercent) {
        preferences(context).edit()
                .putInt(PREF_SCALE_PERCENT, sanitizeScale(scalePercent))
                .apply();
    }

    static void saveBitrate(final Context context, final int bitrateMbps) {
        preferences(context).edit()
                .putInt(PREF_BITRATE_MBPS, sanitizeBitrate(bitrateMbps))
                .apply();
    }

    static void saveAudioMode(
            final Context context,
            final RecordingAudioMode audioMode) {
        preferences(context).edit()
                .putString(
                        PREF_AUDIO_MODE,
                        (audioMode == null
                                ? RecordingAudioMode.AUTO : audioMode)
                                .storedValue())
                .apply();
    }

    static void reset(final Context context) {
        preferences(context).edit()
                .putInt(PREF_SCALE_PERCENT, DEFAULT_SCALE_PERCENT)
                .putInt(PREF_BITRATE_MBPS, DEFAULT_BITRATE_MBPS)
                .putString(
                        PREF_AUDIO_MODE,
                        RecordingAudioMode.AUTO.storedValue())
                .apply();
    }

    static Dimensions scaledDimensions(
            final int sourceWidth,
            final int sourceHeight,
            final int scalePercent) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("invalid recording source size");
        }
        final int scale = sanitizeScale(scalePercent);
        if (scale == DEFAULT_SCALE_PERCENT) {
            return new Dimensions(sourceWidth, sourceHeight);
        }
        return new Dimensions(
                scaledEvenDimension(sourceWidth, scale),
                scaledEvenDimension(sourceHeight, scale));
    }

    static int sanitizeScale(final int scalePercent) {
        return scalePercent == 50 || scalePercent == 75
                || scalePercent == 100
                ? scalePercent : DEFAULT_SCALE_PERCENT;
    }

    static int sanitizeBitrate(final int bitrateMbps) {
        return Math.max(
                MIN_BITRATE_MBPS,
                Math.min(MAX_BITRATE_MBPS, bitrateMbps));
    }

    private static int scaledEvenDimension(
            final int dimension,
            final int scalePercent) {
        final long scaled = (long) dimension * scalePercent / 100L;
        return Math.max(2, (int) scaled & ~1);
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final class Values {
        final int scalePercent;
        final int bitrateMbps;
        final RecordingAudioMode audioMode;

        Values(
                final int scalePercent,
                final int bitrateMbps,
                final RecordingAudioMode audioMode) {
            this.scalePercent = scalePercent;
            this.bitrateMbps = bitrateMbps;
            this.audioMode = audioMode;
        }
    }

    static final class Dimensions {
        final int width;
        final int height;

        Dimensions(final int width, final int height) {
            this.width = width;
            this.height = height;
        }
    }
}
