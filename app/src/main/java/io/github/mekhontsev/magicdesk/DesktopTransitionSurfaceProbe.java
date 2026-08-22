package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Samples one desktop pixel around a self-test task transition. */
public final class DesktopTransitionSurfaceProbe {
    // Window composition can shift an unchanged wallpaper by a few channel
    // levels. Keep that noise distinct from a replaced desktop surface.
    private static final int COLOR_TOLERANCE = 8;

    private DesktopTransitionSurfaceProbe() {
    }

    static String createCaptureCommand(
            final DisplayCaptureSource captureSource,
            final int x,
            final int y) {
        validatePoint(captureSource, x, y);
        return AppProcessCommand.run(
                DesktopTransitionSurfaceProbe.class.getName(),
                "capture " + captureSource.commandArgument()
                        + " " + x + " " + y);
    }

    static Reference parseReference(
            final DisplayCaptureSource captureSource,
            final int x,
            final int y,
            final String output) throws IOException {
        final java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^desktop-pixel=([0-9a-fA-F]{8})$")
                .matcher(output == null ? "" : output);
        if (!matcher.find()) {
            throw new IOException("desktop pixel capture returned no color");
        }
        try {
            return new Reference(
                    captureSource,
                    x,
                    y,
                    (int) Long.parseLong(matcher.group(1), 16));
        } catch (NumberFormatException error) {
            throw new IOException("invalid desktop pixel color", error);
        }
    }

    static boolean parseReportedSurfaceChange(final String output)
            throws IOException {
        final String value = parseReportedValue(
                output, "transition-surface-changed");
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IOException("invalid desktop surface observation: " + value);
    }

    static String parseReportedSamples(final String output)
            throws IOException {
        return parseReportedValue(output, "transition-pixels");
    }

    static String parseReportedError(final String output) {
        try {
            return parseReportedValue(output, "transition-probe-error");
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String parseReportedValue(
            final String output,
            final String key) throws IOException {
        final java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^" + java.util.regex.Pattern.quote(key)
                        + "=(.*)$")
                .matcher(output == null ? "" : output);
        if (!matcher.find()) {
            throw new IOException("task transfer returned no " + key);
        }
        return matcher.group(1).trim();
    }

    static Observation begin(final Reference reference) {
        return new Observation(reference);
    }

    public static void main(final String[] args) {
        if (args.length != 4 || !"capture".equals(args[0])) {
            System.err.println("usage: DesktopTransitionSurfaceProbe "
                    + "capture <capture-source> <x> <y>");
            System.exit(64);
            return;
        }
        try {
            final DisplayCaptureSource captureSource =
                    DisplayCaptureSource.parse(args[1]);
            final int x = Integer.parseInt(args[2]);
            final int y = Integer.parseInt(args[3]);
            validatePoint(captureSource, x, y);
            System.out.println("desktop-pixel="
                    + formatColor(capturePixel(captureSource, x, y)));
        } catch (IOException | RuntimeException error) {
            System.err.println("desktop pixel capture failed: "
                    + usefulMessage(error));
            System.exit(1);
        }
    }

    private static int capturePixel(
            final DisplayCaptureSource captureSource,
            final int x,
            final int y) throws IOException {
        return DisplayPixelProbe.capturePixel(captureSource, x, y);
    }

    private static void validatePoint(
            final DisplayCaptureSource captureSource,
            final int x,
            final int y) {
        if (captureSource == null || x < 0 || y < 0) {
            throw new IllegalArgumentException("invalid desktop sample point");
        }
    }

    static boolean sameColor(final int first, final int second) {
        return Math.abs(((first >>> 16) & 0xFF)
                        - ((second >>> 16) & 0xFF)) <= COLOR_TOLERANCE
                && Math.abs(((first >>> 8) & 0xFF)
                        - ((second >>> 8) & 0xFF)) <= COLOR_TOLERANCE
                && Math.abs((first & 0xFF) - (second & 0xFF))
                        <= COLOR_TOLERANCE;
    }

    static String formatColor(final int color) {
        return String.format(java.util.Locale.US, "%08x", color);
    }

    private static String usefulMessage(final Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    static final class Reference {
        final DisplayCaptureSource captureSource;
        final int x;
        final int y;
        final int color;

        Reference(
                final DisplayCaptureSource captureSource,
                final int x,
                final int y,
                final int color) {
            validatePoint(captureSource, x, y);
            this.captureSource = captureSource;
            this.x = x;
            this.y = y;
            this.color = color;
        }

        String commandArguments() {
            return captureSource.commandArgument() + " " + x + " " + y + " "
                    + formatColor(color);
        }

        static Reference parse(final String[] args, final int offset) {
            return new Reference(
                    DisplayCaptureSource.parse(args[offset]),
                    Integer.parseInt(args[offset + 1]),
                    Integer.parseInt(args[offset + 2]),
                    (int) Long.parseLong(args[offset + 3], 16));
        }
    }

    static final class Observation {
        private final Reference mReference;
        private final List<String> mSamples = new ArrayList<>();
        private boolean mChanged;
        private String mError = "";

        Observation(final Reference reference) {
            mReference = reference;
            mSamples.add("start:" + formatColor(reference.color));
        }

        void sample(final String stage, final int color) {
            if (!mError.isEmpty()) {
                return;
            }
            mSamples.add(stage + ":" + formatColor(color));
            mChanged |= !sameColor(mReference.color, color);
        }

        void sample(final String stage) {
            if (!mError.isEmpty()) {
                return;
            }
            try {
                final int color = capturePixel(
                        mReference.captureSource,
                        mReference.x,
                        mReference.y);
                sample(stage, color);
            } catch (IOException error) {
                mError = usefulMessage(error);
            }
        }

        void recordError(final String error) {
            if (mError.isEmpty()) {
                mError = error == null ? "" : error;
            }
        }

        Result finish() {
            return new Result(mChanged, mSamples, mError);
        }
    }

    static final class Result {
        final boolean surfaceChanged;
        final List<String> samples;
        final String error;

        Result(
                final boolean surfaceChanged,
                final List<String> samples,
                final String error) {
            this.surfaceChanged = surfaceChanged;
            this.samples = Collections.unmodifiableList(
                    new ArrayList<>(samples));
            this.error = error == null ? "" : error;
        }
    }
}
