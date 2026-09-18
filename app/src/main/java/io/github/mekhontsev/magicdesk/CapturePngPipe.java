package io.github.mekhontsev.magicdesk;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.io.OutputStream;

/** The writer owns the bitmap; reliable-pipe errors reach the capture caller. */
final class CapturePngPipe {
    interface Source { Bitmap capture() throws IOException; }

    static ParcelFileDescriptor open(final Source source) throws IOException {
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        final Thread writer = new Thread(() -> {
            Bitmap bitmap = null;
            try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                try {
                    bitmap = source.capture();
                    if (bitmap == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IOException("PNG encoding failed");
                    }
                } catch (IOException | RuntimeException error) {
                    final String message = error.getMessage();
                    pipe[1].closeWithError(message == null || message.isEmpty()
                            ? error.getClass().getSimpleName() : message);
                }
            } catch (IOException ignored) {
                // Reader cancellation owns this outcome; there is no fallback image.
            } finally {
                if (bitmap != null) bitmap.recycle();
            }
        }, "MagicDeskCapture");
        writer.setDaemon(true);
        try {
            writer.start();
        } catch (RuntimeException | Error error) {
            try { pipe[0].close(); } catch (IOException ignored) { }
            try { pipe[1].close(); } catch (IOException ignored) { }
            throw error;
        }
        return pipe[0];
    }

    private CapturePngPipe() { }
}
