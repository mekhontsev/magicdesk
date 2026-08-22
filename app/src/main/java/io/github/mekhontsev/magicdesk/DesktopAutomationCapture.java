package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Shared in-memory display capture used by MCP screenshots and pixel probes. */
final class DesktopAutomationCapture {
    private static final int MAX_PIXEL_SAMPLES = 64;
    private static final int MAX_CAPTURE_BYTES = 32 * 1024 * 1024;

    private final Context mContext;

    DesktopAutomationCapture(final Context context) {
        mContext = context.getApplicationContext();
    }

    DesktopAutomationResult screenshot(final Integer requestedDisplayId) {
        try {
            final Target target = resolve(requestedDisplayId);
            final byte[] png;
            try (InputStream input =
                         new ParcelFileDescriptor.AutoCloseInputStream(
                                 ShellAccess.openDisplayCapture(
                                         target.source,
                                         new Rect(0, 0,
                                                 target.width,
                                                 target.height),
                                         target.width,
                                         target.height))) {
                png = readBounded(input);
            }
            if (png.length == 0) {
                throw new IOException("display capture returned no image");
            }
            final JSONObject data = new JSONObject()
                    .put("displayId", target.displayId)
                    .put("width", target.width)
                    .put("height", target.height)
                    .put("mimeType", "image/png")
                    .put("captureSource", target.source.commandArgument());
            return DesktopAutomationResult.success(
                    "desktop screenshot captured",
                    data,
                    new DesktopAutomationImage(
                            "image/png",
                            Base64.encodeToString(
                                    png, Base64.NO_WRAP)));
        } catch (IOException | JSONException | RuntimeException error) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.CAPTURE_UNAVAILABLE,
                    ShellAccess.usefulMessage(error), true);
        }
    }

    DesktopAutomationResult samplePixels(final JSONObject arguments) {
        try {
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final Integer displayId = args.has("displayId")
                    ? Integer.valueOf(args.getInt("displayId")) : null;
            final Target target = resolve(displayId);
            final JSONArray points = args.optJSONArray("points");
            if (points == null || points.length() == 0
                    || points.length() > MAX_PIXEL_SAMPLES) {
                throw new IllegalArgumentException(
                        "points must contain 1 to " + MAX_PIXEL_SAMPLES
                                + " coordinates");
            }
            final JSONArray samples = new JSONArray();
            final int[] xCoordinates = new int[points.length()];
            final int[] yCoordinates = new int[points.length()];
            for (int index = 0; index < points.length(); index++) {
                final JSONObject point = points.getJSONObject(index);
                final int x = point.getInt("x");
                final int y = point.getInt("y");
                if (x < 0 || y < 0 || x >= target.width
                        || y >= target.height) {
                    throw new IllegalArgumentException(
                            "pixel coordinate is outside the display");
                }
                xCoordinates[index] = x;
                yCoordinates[index] = y;
            }
            final int[] colors = ShellAccess.captureDisplayPixels(
                    target.source, xCoordinates, yCoordinates);
            for (int index = 0; index < colors.length; index++) {
                final int color = colors[index];
                samples.put(new JSONObject()
                        .put("x", xCoordinates[index])
                        .put("y", yCoordinates[index])
                        .put("argb", String.format(
                                Locale.ROOT, "#%08X", color))
                        .put("alpha", (color >>> 24) & 0xFF)
                        .put("red", (color >>> 16) & 0xFF)
                        .put("green", (color >>> 8) & 0xFF)
                        .put("blue", color & 0xFF));
            }
            return DesktopAutomationResult.success(
                    "display pixels sampled",
                    new JSONObject()
                            .put("displayId", target.displayId)
                            .put("width", target.width)
                            .put("height", target.height)
                            .put("captureSource",
                                    target.source.commandArgument())
                            .put("samples", samples));
        } catch (IllegalArgumentException | JSONException error) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    ShellAccess.usefulMessage(error), false);
        } catch (IOException | RuntimeException error) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.CAPTURE_UNAVAILABLE,
                    ShellAccess.usefulMessage(error), true);
        }
    }

    private Target resolve(final Integer requestedDisplayId)
            throws IOException {
        final int activeDisplayId = DesktopRuntimeBridge
                .getActiveDesktopDisplayId();
        final int displayId = requestedDisplayId == null
                ? activeDisplayId : requestedDisplayId.intValue();
        if (displayId < Display.DEFAULT_DISPLAY
                || displayId != activeDisplayId) {
            throw new IOException(
                    "the display has no active desktop session");
        }
        final DisplayManager manager =
                mContext.getSystemService(DisplayManager.class);
        final Display display = manager == null
                ? null : manager.getDisplay(displayId);
        if (display == null) {
            throw new IOException("display is unavailable");
        }
        final Point size = new Point();
        display.getRealSize(size);
        if (size.x <= 0 || size.y <= 0) {
            throw new IOException("display has invalid dimensions");
        }
        return new Target(
                displayId,
                size.x,
                size.y,
                DesktopDisplayDrivers.captureSource(displayId));
    }

    private static byte[] readBounded(final InputStream input)
            throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_CAPTURE_BYTES) {
                throw new IOException("display capture is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static final class Target {
        final int displayId;
        final int width;
        final int height;
        final DisplayCaptureSource source;

        Target(
                final int displayId,
                final int width,
                final int height,
                final DisplayCaptureSource source) {
            this.displayId = displayId;
            this.width = width;
            this.height = height;
            this.source = source;
        }
    }
}
