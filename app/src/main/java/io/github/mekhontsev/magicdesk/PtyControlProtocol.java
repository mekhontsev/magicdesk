package io.github.mekhontsev.magicdesk;

import java.io.DataOutput;
import java.io.IOException;

/** Binary control frames consumed by magicdesk_pty_bridge. */
final class PtyControlProtocol {
    static final int MAX_DATA_BYTES = 1024 * 1024;

    private static final int FRAME_DATA = 1;
    private static final int FRAME_RESIZE = 2;

    private PtyControlProtocol() {
    }

    static void writeData(final DataOutput output, final byte[] data)
            throws IOException {
        if (data == null || data.length == 0) {
            return;
        }
        if (data.length > MAX_DATA_BYTES) {
            throw new IOException("PTY input frame is too large");
        }
        output.writeByte(FRAME_DATA);
        output.writeInt(data.length);
        output.write(data);
    }

    static void writeResize(
            final DataOutput output, final int rows, final int columns)
            throws IOException {
        if (rows < 2 || rows > 65535
                || columns < 2 || columns > 65535) {
            throw new IOException("invalid PTY dimensions");
        }
        output.writeByte(FRAME_RESIZE);
        output.writeInt(8);
        output.writeInt(rows);
        output.writeInt(columns);
    }
}
