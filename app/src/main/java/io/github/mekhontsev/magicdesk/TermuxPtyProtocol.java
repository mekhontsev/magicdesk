package io.github.mekhontsev.magicdesk;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Framing sent from the Termux-hosted PTY bridge to MagicDesk. */
final class TermuxPtyProtocol {
    static final int FRAME_HELLO = 17;
    static final int FRAME_OUTPUT = 18;
    static final int FRAME_CWD = 19;
    static final int MAX_FRAME_BYTES = 1024 * 1024;

    private TermuxPtyProtocol() {
    }

    static Frame readFrame(final DataInputStream input) throws IOException {
        final int type;
        try {
            type = input.readUnsignedByte();
        } catch (EOFException error) {
            return null;
        }
        final int length = input.readInt();
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("invalid Termux PTY frame length");
        }
        final byte[] payload = new byte[length];
        input.readFully(payload);
        return new Frame(type, payload);
    }

    static Hello parseHello(final Frame frame, final String expectedToken)
            throws IOException {
        if (frame == null || frame.type != FRAME_HELLO) {
            throw new IOException("missing Termux PTY handshake");
        }
        final String value = new String(
                frame.payload, StandardCharsets.US_ASCII);
        final int separator = value.lastIndexOf(' ');
        if (separator < 1
                || !constantTimeEquals(
                        value.substring(0, separator), expectedToken)) {
            throw new IOException("invalid Termux PTY handshake");
        }
        try {
            final long processId = Long.parseLong(
                    value.substring(separator + 1));
            if (processId <= 0L) {
                throw new NumberFormatException("non-positive pid");
            }
            return new Hello(processId);
        } catch (NumberFormatException error) {
            throw new IOException("invalid Termux PTY process id", error);
        }
    }

    private static boolean constantTimeEquals(
            final String first, final String second) {
        if (first == null || second == null) {
            return false;
        }
        int difference = first.length() ^ second.length();
        final int count = Math.max(first.length(), second.length());
        for (int index = 0; index < count; index++) {
            final char left = index < first.length() ? first.charAt(index) : 0;
            final char right = index < second.length() ? second.charAt(index) : 0;
            difference |= left ^ right;
        }
        return difference == 0;
    }

    static final class Frame {
        final int type;
        final byte[] payload;

        Frame(final int type, final byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    static final class Hello {
        final long processId;

        Hello(final long processId) {
            this.processId = processId;
        }
    }
}
