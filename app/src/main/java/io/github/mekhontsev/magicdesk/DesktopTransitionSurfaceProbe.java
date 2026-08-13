package io.github.mekhontsev.magicdesk;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.IBinder;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Samples one desktop pixel around a self-test task transition. */
public final class DesktopTransitionSurfaceProbe {
    private static final int COLOR_TOLERANCE = 4;

    private DesktopTransitionSurfaceProbe() {
    }

    static String createCaptureCommand(
            final int displayId,
            final int x,
            final int y) {
        validatePoint(displayId, x, y);
        return AppProcessCommand.run(
                DesktopTransitionSurfaceProbe.class.getName(),
                "capture " + displayId + " " + x + " " + y);
    }

    static Reference parseReference(
            final int displayId,
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
                    displayId,
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
                    + "capture <display-id> <x> <y>");
            System.exit(64);
            return;
        }
        try {
            final int displayId = Integer.parseInt(args[1]);
            final int x = Integer.parseInt(args[2]);
            final int y = Integer.parseInt(args[3]);
            validatePoint(displayId, x, y);
            System.out.println("desktop-pixel="
                    + formatColor(capturePixel(displayId, x, y)));
        } catch (IOException | RuntimeException error) {
            System.err.println("desktop pixel capture failed: "
                    + usefulMessage(error));
            System.exit(1);
        }
    }

    private static int capturePixel(
            final int displayId,
            final int x,
            final int y) throws IOException {
        Bitmap hardwareBitmap = null;
        Bitmap softwareBitmap = null;
        HardwareBuffer hardwareBuffer = null;
        try {
            final Class<?> managerClass = Class.forName(
                    "android.hardware.display.DisplayManagerGlobal");
            final Object manager = managerClass.getMethod("getInstance")
                    .invoke(null);
            final IBinder displayToken = (IBinder) managerClass.getMethod(
                    "getDisplayToken", Integer.TYPE)
                    .invoke(manager, Integer.valueOf(displayId));
            if (displayToken == null) {
                throw new IOException(
                        "display capture token is unavailable for " + displayId);
            }

            final Class<?> builderClass = Class.forName(
                    "android.window.ScreenCapture$DisplayCaptureArgs$Builder");
            final Object builder = builderClass
                    .getConstructor(IBinder.class)
                    .newInstance(displayToken);
            builderClass.getMethod("setSourceCrop", Rect.class)
                    .invoke(builder, new Rect(x, y, x + 1, y + 1));
            builderClass.getMethod(
                    "setSize", Integer.TYPE, Integer.TYPE)
                    .invoke(builder, Integer.valueOf(1), Integer.valueOf(1));
            final Object captureArgs = builderClass.getMethod("build")
                    .invoke(builder);
            final Class<?> captureArgsClass = Class.forName(
                    "android.window.ScreenCapture$DisplayCaptureArgs");
            final Object screenshot = Class.forName(
                    "android.window.ScreenCapture")
                    .getMethod("captureDisplay", captureArgsClass)
                    .invoke(null, captureArgs);
            if (screenshot == null) {
                throw new IOException("display capture returned no buffer");
            }
            hardwareBuffer = (HardwareBuffer) screenshot.getClass()
                    .getMethod("getHardwareBuffer")
                    .invoke(screenshot);
            hardwareBitmap = (Bitmap) screenshot.getClass()
                    .getMethod("asBitmap")
                    .invoke(screenshot);
            if (hardwareBitmap == null) {
                throw new IOException("display capture returned no bitmap");
            }
            softwareBitmap = hardwareBitmap.copy(
                    Bitmap.Config.ARGB_8888, false);
            if (softwareBitmap == null) {
                throw new IOException("display capture could not be read");
            }
            return softwareBitmap.getPixel(0, 0);
        } catch (ClassNotFoundException
                | NoSuchMethodException
                | IllegalAccessException
                | InstantiationException error) {
            throw new IOException(
                    "in-memory display capture API is unavailable", error);
        } catch (InvocationTargetException error) {
            final Throwable cause = error.getCause();
            throw new IOException(
                    "in-memory display capture failed: "
                            + usefulMessage(cause),
                    cause == null ? error : cause);
        } catch (RuntimeException error) {
            throw new IOException(
                    "in-memory display capture failed: "
                            + usefulMessage(error), error);
        } finally {
            if (softwareBitmap != null) {
                softwareBitmap.recycle();
            }
            if (hardwareBitmap != null) {
                hardwareBitmap.recycle();
            }
            if (hardwareBuffer != null) {
                hardwareBuffer.close();
            }
        }
    }

    private static void validatePoint(
            final int displayId,
            final int x,
            final int y) {
        if (displayId < 0 || x < 0 || y < 0) {
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
        final int displayId;
        final int x;
        final int y;
        final int color;

        Reference(
                final int displayId,
                final int x,
                final int y,
                final int color) {
            validatePoint(displayId, x, y);
            this.displayId = displayId;
            this.x = x;
            this.y = y;
            this.color = color;
        }

        String commandArguments() {
            return displayId + " " + x + " " + y + " "
                    + formatColor(color);
        }

        static Reference parse(final String[] args, final int offset) {
            return new Reference(
                    Integer.parseInt(args[offset]),
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

        void sample(final String stage) {
            if (!mError.isEmpty()) {
                return;
            }
            try {
                final int color = capturePixel(
                        mReference.displayId,
                        mReference.x,
                        mReference.y);
                mSamples.add(stage + ":" + formatColor(color));
                mChanged |= !sameColor(mReference.color, color);
            } catch (IOException error) {
                mError = usefulMessage(error);
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
