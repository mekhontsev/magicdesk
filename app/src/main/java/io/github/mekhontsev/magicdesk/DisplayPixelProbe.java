package io.github.mekhontsev.magicdesk;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.IBinder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Reads small in-memory samples from a display for explicit diagnostics. */
final class DisplayPixelProbe {
    private static final int VISUAL_CONTRAST = 16;
    private static final int SIGNATURE_CHANNEL_DIFFERENCE = 2;

    private DisplayPixelProbe() {
    }

    static int capturePixel(
            final int displayId,
            final int x,
            final int y) throws IOException {
        return capturePixel(DisplayCaptureSource.logical(displayId), x, y);
    }

    static int capturePixel(
            final DisplayCaptureSource source,
            final int x,
            final int y) throws IOException {
        final Bitmap bitmap = capture(
                source, new Rect(x, y, x + 1, y + 1), 1, 1);
        try {
            return bitmap.getPixel(0, 0);
        } finally {
            bitmap.recycle();
        }
    }

    static RegionStats captureRegion(
            final int displayId,
            final Rect sourceCrop,
            final int sampleWidth,
            final int sampleHeight) throws IOException {
        return captureRegion(
                DisplayCaptureSource.logical(displayId),
                sourceCrop,
                sampleWidth,
                sampleHeight);
    }

    static RegionStats captureRegion(
            final DisplayCaptureSource source,
            final Rect sourceCrop,
            final int sampleWidth,
            final int sampleHeight) throws IOException {
        final Bitmap bitmap = capture(
                source, sourceCrop, sampleWidth, sampleHeight);
        try {
            final int[] pixels = new int[sampleWidth * sampleHeight];
            bitmap.getPixels(
                    pixels, 0, sampleWidth,
                    0, 0, sampleWidth, sampleHeight);
            return analyze(pixels, sampleWidth, sampleHeight);
        } finally {
            bitmap.recycle();
        }
    }

    static RegionStats analyze(
            final int[] pixels,
            final int width,
            final int height) {
        if (pixels == null || width <= 0 || height <= 0
                || pixels.length != width * height) {
            throw new IllegalArgumentException("invalid display pixel sample");
        }
        final Map<Integer, Integer> counts = new HashMap<>();
        int dominantRgb = 0;
        int dominantCount = 0;
        for (final int pixel : pixels) {
            final int rgb = pixel & 0x00FFFFFF;
            final int count = counts.getOrDefault(
                    Integer.valueOf(rgb), Integer.valueOf(0)).intValue() + 1;
            counts.put(Integer.valueOf(rgb), Integer.valueOf(count));
            if (count > dominantCount) {
                dominantRgb = rgb;
                dominantCount = count;
            }
        }

        int maxContrast = 0;
        int contrastingPixels = 0;
        for (final int pixel : pixels) {
            final int contrast = colorDistance(dominantRgb, pixel);
            maxContrast = Math.max(maxContrast, contrast);
            if (contrast >= VISUAL_CONTRAST) {
                contrastingPixels++;
            }
        }
        final int minimumContrastingPixels = Math.max(6, pixels.length / 200);
        return new RegionStats(
                width,
                height,
                0xFF000000 | dominantRgb,
                maxContrast,
                contrastingPixels,
                maxContrast >= VISUAL_CONTRAST
                        && contrastingPixels >= minimumContrastingPixels,
                RegionSignature.fromPixels(pixels, width, height));
    }

    private static Bitmap capture(
            final DisplayCaptureSource source,
            final Rect sourceCrop,
            final int outputWidth,
            final int outputHeight) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("display capture source is required");
        }
        validateRegion(
                source.logicalDisplayId,
                sourceCrop,
                outputWidth,
                outputHeight);
        return source.isPhysical()
                ? capturePhysical(source, sourceCrop, outputWidth, outputHeight)
                : captureLogical(
                        source.logicalDisplayId,
                        sourceCrop,
                        outputWidth,
                        outputHeight);
    }

    private static Bitmap captureLogical(
            final int displayId,
            final Rect sourceCrop,
            final int outputWidth,
            final int outputHeight) throws IOException {
        Bitmap hardwareBitmap = null;
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
                    .invoke(builder, new Rect(sourceCrop));
            builderClass.getMethod(
                    "setSize", Integer.TYPE, Integer.TYPE)
                    .invoke(builder,
                            Integer.valueOf(outputWidth),
                            Integer.valueOf(outputHeight));
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
            final Bitmap softwareBitmap = hardwareBitmap.copy(
                    Bitmap.Config.ARGB_8888, false);
            if (softwareBitmap == null) {
                throw new IOException("display capture could not be read");
            }
            return softwareBitmap;
        } catch (ClassNotFoundException
                | NoSuchMethodException
                | IllegalAccessException
                | InstantiationException error) {
            throw new UnavailableException(
                    "in-memory display capture API is unavailable", error);
        } catch (InvocationTargetException error) {
            final Throwable cause = error.getCause();
            if (cause instanceof SecurityException) {
                throw new UnavailableException(
                        "in-memory display capture is not permitted", cause);
            }
            throw new IOException(
                    "in-memory display capture failed: "
                            + usefulMessage(cause),
                    cause == null ? error : cause);
        } catch (SecurityException error) {
            throw new UnavailableException(
                    "in-memory display capture is not permitted", error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "in-memory display capture failed: "
                            + usefulMessage(error), error);
        } finally {
            if (hardwareBitmap != null) {
                hardwareBitmap.recycle();
            }
            if (hardwareBuffer != null) {
                hardwareBuffer.close();
            }
        }
    }

    private static Bitmap capturePhysical(
            final DisplayCaptureSource source,
            final Rect sourceCrop,
            final int outputWidth,
            final int outputHeight) throws IOException {
        Process process = null;
        Bitmap fullFrame = null;
        try {
            process = new ProcessBuilder(
                    "/system/bin/screencap",
                    "-p",
                    "-d",
                    source.physicalDisplayId).start();
            fullFrame = BitmapFactory.decodeStream(process.getInputStream());
            final String error = readText(process.getErrorStream());
            final int exitCode = process.waitFor();
            if (exitCode != 0 || fullFrame == null) {
                throw new IOException(
                        "physical display capture failed"
                                + (error.isEmpty() ? "" : ": " + error));
            }
            if (sourceCrop.right > fullFrame.getWidth()
                    || sourceCrop.bottom > fullFrame.getHeight()) {
                throw new IOException(
                        "display capture region " + sourceCrop.toShortString()
                                + " exceeds " + fullFrame.getWidth() + "x"
                                + fullFrame.getHeight());
            }
            final Bitmap sampled = Bitmap.createBitmap(
                    outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(sampled);
            final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(
                    fullFrame,
                    sourceCrop,
                    new Rect(0, 0, outputWidth, outputHeight),
                    paint);
            return sampled;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("physical display capture interrupted", error);
        } finally {
            if (fullFrame != null) {
                fullFrame.recycle();
            }
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String readText(final InputStream stream)
            throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[1_024];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name()).trim();
    }

    private static int colorDistance(final int firstRgb, final int second) {
        return Math.max(
                Math.abs(((firstRgb >>> 16) & 0xFF)
                        - ((second >>> 16) & 0xFF)),
                Math.max(
                        Math.abs(((firstRgb >>> 8) & 0xFF)
                                - ((second >>> 8) & 0xFF)),
                        Math.abs((firstRgb & 0xFF) - (second & 0xFF))));
    }

    private static void validateRegion(
            final int displayId,
            final Rect sourceCrop,
            final int outputWidth,
            final int outputHeight) {
        if (displayId < 0 || sourceCrop == null || sourceCrop.isEmpty()
                || sourceCrop.left < 0 || sourceCrop.top < 0
                || outputWidth <= 0 || outputHeight <= 0) {
            throw new IllegalArgumentException("invalid display capture region");
        }
    }

    private static String usefulMessage(final Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    static final class RegionStats {
        final int width;
        final int height;
        final int dominantColor;
        final int maxContrast;
        final int contrastingPixels;
        final boolean visuallyVaried;
        final RegionSignature signature;

        RegionStats(
                final int width,
                final int height,
                final int dominantColor,
                final int maxContrast,
                final int contrastingPixels,
                final boolean visuallyVaried,
                final RegionSignature signature) {
            this.width = width;
            this.height = height;
            this.dominantColor = dominantColor;
            this.maxContrast = maxContrast;
            this.contrastingPixels = contrastingPixels;
            this.visuallyVaried = visuallyVaried;
            this.signature = signature;
        }
    }

    /** Compact 12-bit RGB sample used to compare the same display region. */
    static final class RegionSignature {
        private final int width;
        private final int height;
        private final String encoded;

        private RegionSignature(
                final int width,
                final int height,
                final String encoded) {
            if (width <= 0 || height <= 0 || encoded == null
                    || encoded.length() != width * height * 3) {
                throw new IllegalArgumentException(
                        "invalid display region signature");
            }
            for (int i = 0; i < encoded.length(); i++) {
                if (Character.digit(encoded.charAt(i), 16) < 0) {
                    throw new IllegalArgumentException(
                            "invalid display region signature");
                }
            }
            this.width = width;
            this.height = height;
            this.encoded = encoded.toLowerCase(java.util.Locale.ROOT);
        }

        static RegionSignature fromPixels(
                final int[] pixels,
                final int width,
                final int height) {
            if (pixels == null || width <= 0 || height <= 0
                    || pixels.length != width * height) {
                throw new IllegalArgumentException(
                        "invalid display pixel sample");
            }
            final StringBuilder encoded = new StringBuilder(
                    pixels.length * 3);
            for (final int pixel : pixels) {
                encoded.append(Character.forDigit((pixel >>> 20) & 0xF, 16));
                encoded.append(Character.forDigit((pixel >>> 12) & 0xF, 16));
                encoded.append(Character.forDigit((pixel >>> 4) & 0xF, 16));
            }
            return new RegionSignature(width, height, encoded.toString());
        }

        static RegionSignature decode(
                final int width,
                final int height,
                final String encoded) {
            return new RegionSignature(width, height, encoded);
        }

        String encode() {
            return encoded;
        }

        RegionDifference compare(final RegionSignature reference) {
            if (reference == null
                    || width != reference.width
                    || height != reference.height) {
                throw new IllegalArgumentException(
                        "display region signatures do not match");
            }
            int changedPixels = 0;
            for (int offset = 0; offset < encoded.length(); offset += 3) {
                final int redDifference = channelDifference(
                        encoded, reference.encoded, offset);
                final int greenDifference = channelDifference(
                        encoded, reference.encoded, offset + 1);
                final int blueDifference = channelDifference(
                        encoded, reference.encoded, offset + 2);
                if (Math.max(redDifference,
                        Math.max(greenDifference, blueDifference))
                        >= SIGNATURE_CHANNEL_DIFFERENCE) {
                    changedPixels++;
                }
            }
            return new RegionDifference(changedPixels, width * height);
        }

        private static int channelDifference(
                final String first,
                final String second,
                final int offset) {
            return Math.abs(
                    Character.digit(first.charAt(offset), 16)
                            - Character.digit(second.charAt(offset), 16));
        }
    }

    static final class RegionDifference {
        final int changedPixels;
        final int totalPixels;
        final boolean materiallyDifferent;

        RegionDifference(final int changedPixels, final int totalPixels) {
            this.changedPixels = changedPixels;
            this.totalPixels = totalPixels;
            final int minimumChangedPixels = Math.max(12, totalPixels / 10);
            this.materiallyDifferent = changedPixels >= minimumChangedPixels;
        }
    }

    static final class UnavailableException extends IOException {
        private static final long serialVersionUID = 1L;

        UnavailableException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
