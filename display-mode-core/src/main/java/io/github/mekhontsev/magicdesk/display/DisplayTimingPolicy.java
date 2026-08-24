package io.github.mekhontsev.magicdesk.display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parsing and selection policy shared by privileged display clients. */
public final class DisplayTimingPolicy {
    private static final Pattern NUBIA_MODE_PATTERN = Pattern.compile(
            "^(\\d+)x(\\d+)\\s+(\\d+)\\s+(\\d+)$");

    private DisplayTimingPolicy() {
    }

    public static List<ParsedTiming> parseNubiaModes(final String output) {
        final ArrayList<ParsedTiming> modes = new ArrayList<>();
        if (output == null) {
            return modes;
        }
        for (final String line : output.split("\\r?\\n")) {
            final Matcher matcher = NUBIA_MODE_PATTERN.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            try {
                final ParsedTiming mode = new ParsedTiming(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(4)));
                if (mode.isValid()) {
                    modes.add(mode);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed vendor node entries.
            }
        }
        return modes;
    }

    public static <T extends DisplayTiming> List<T> normalize(
            final List<T> modes) {
        final Map<String, T> uniqueModes = new LinkedHashMap<>();
        for (final T mode : modes) {
            final String timing = mode.timingKey();
            final T existing = uniqueModes.get(timing);
            if (existing == null
                    || mode.pictureAspect() > existing.pictureAspect()) {
                uniqueModes.put(timing, mode);
            }
        }
        final ArrayList<T> sorted = new ArrayList<>(uniqueModes.values());
        Collections.sort(sorted, new Comparator<T>() {
            @Override
            public int compare(final T left, final T right) {
                int result = Integer.compare(right.height(), left.height());
                if (result == 0) {
                    result = Integer.compare(right.width(), left.width());
                }
                if (result == 0) {
                    result = Integer.compare(
                            right.refreshRate(), left.refreshRate());
                }
                return result;
            }
        });
        return Collections.unmodifiableList(sorted);
    }

    public static <T extends DisplayTiming> T find(
            final List<T> modes,
            final String timingKey) {
        if (timingKey == null || timingKey.isEmpty()) {
            return null;
        }
        for (final T mode : modes) {
            if (timingKey.equals(mode.timingKey())) {
                return mode;
            }
        }
        return null;
    }

    public static <T extends DisplayTiming> T bestNative(
            final List<T> modes) {
        int maxHeight = 0;
        for (final T mode : modes) {
            maxHeight = Math.max(maxHeight, mode.height());
        }

        boolean hasNonCinemaMode = false;
        for (final T mode : modes) {
            if (mode.height() == maxHeight && mode.pictureAspect() != 4) {
                hasNonCinemaMode = true;
                break;
            }
        }

        T best = null;
        for (final T mode : modes) {
            if (mode.height() != maxHeight
                    || (hasNonCinemaMode && mode.pictureAspect() == 4)) {
                continue;
            }
            if (best == null
                    || mode.width() > best.width()
                    || (mode.width() == best.width()
                            && mode.refreshRate() > best.refreshRate())
                    || (mode.width() == best.width()
                            && mode.refreshRate() == best.refreshRate()
                            && mode.pictureAspect() > best.pictureAspect())) {
                best = mode;
            }
        }
        return best;
    }

    public static final class ParsedTiming implements DisplayTiming {
        private final int mWidth;
        private final int mHeight;
        private final int mRefreshRate;
        private final int mPictureAspect;

        public ParsedTiming(
                final int width,
                final int height,
                final int refreshRate,
                final int pictureAspect) {
            mWidth = width;
            mHeight = height;
            mRefreshRate = refreshRate;
            mPictureAspect = pictureAspect;
        }

        @Override
        public int width() {
            return mWidth;
        }

        @Override
        public int height() {
            return mHeight;
        }

        @Override
        public int refreshRate() {
            return mRefreshRate;
        }

        @Override
        public int pictureAspect() {
            return mPictureAspect;
        }

        @Override
        public String toString() {
            return timingKey() + " aspect=" + mPictureAspect;
        }
    }
}
