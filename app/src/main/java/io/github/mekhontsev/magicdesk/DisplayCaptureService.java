package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.ParcelFileDescriptor;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** On-demand capture. Callers own selection and publication, not a second capture backend. */
final class DisplayCaptureService {
    private static final int MAX_CAPTURE_BYTES = 32 * 1024 * 1024;
    private final Context mContext;

    DisplayCaptureService(final Context context) { mContext = context.getApplicationContext(); }

    Image capture(final DisplayCaptureRequest request) throws IOException {
        if (request == null) throw new IllegalArgumentException("capture request is required");
        final Frame display = resolve(request.displayId());
        final DisplayCaptureRequest.Region region = request.regionFor(display.width(), display.height());
        if (region.width() > 8192 || region.height() > 8192) {
            throw new IllegalArgumentException("capture dimensions exceed 8192 pixels");
        }
        final byte[] png;
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                ShellAccess.openDisplayCapture(display.source(),
                        new Rect(region.left(), region.top(), region.right(), region.bottom()),
                        region.width(), region.height()))) {
            png = readBounded(input);
        }
        // Reject an observed geometry change instead of returning stale coordinate metadata.
        display.requireSameGeometry(resolve(display.displayId()));
        return new Image(display, region, png);
    }

    Samples samplePixels(final int displayId, final int[] x, final int[] y) throws IOException {
        if (x == null || y == null || x.length == 0 || x.length != y.length || x.length > 64) {
            throw new IllegalArgumentException("points must contain 1 to 64 coordinates");
        }
        final Frame display = resolve(displayId);
        for (int i = 0; i < x.length; i++) display.requirePixel(x[i], y[i]);
        final int[] colors = ShellAccess.captureDisplayPixels(display.source(), x, y);
        display.requireSameGeometry(resolve(displayId));
        return new Samples(display, colors);
    }

    private Frame resolve(final int displayId) throws IOException {
        if (displayId < 0) throw new IllegalArgumentException("invalid display id");
        final DisplayManager manager = mContext.getSystemService(DisplayManager.class);
        final Display display = manager == null ? null : manager.getDisplay(displayId);
        if (display == null) throw new IOException("display is unavailable");
        final Point size = new Point();
        display.getRealSize(size);
        if (size.x <= 0 || size.y <= 0) throw new IOException("display has invalid dimensions");
        return new Frame(displayId, size.x, size.y, display.getRotation());
    }

    private static byte[] readBounded(final InputStream input) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > MAX_CAPTURE_BYTES - output.size()) throw new IOException("display capture is too large");
            output.write(buffer, 0, read);
        }
        if (output.size() == 0) throw new IOException("display capture returned no image");
        return output.toByteArray();
    }

    record Frame(int displayId, int width, int height, int rotation) {
        DisplayCaptureSource source() { return DisplayCaptureSource.logical(displayId); }

        void requirePixel(final int x, final int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) {
                throw new IllegalArgumentException("pixel coordinate is outside the display");
            }
        }

        void requireSameGeometry(final Frame current) throws IOException {
            if (!equals(current)) throw new IOException("display changed during capture; inspect its geometry again");
        }
    }

    record Image(Frame display, DisplayCaptureRequest.Region region, byte[] png) { }
    record Samples(Frame display, int[] colors) { }
}
