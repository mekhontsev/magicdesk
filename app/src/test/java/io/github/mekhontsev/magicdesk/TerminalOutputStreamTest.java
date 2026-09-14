package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

public final class TerminalOutputStreamTest {
    @Test public void rawBytesAreUnchangedAndWritesAreBounded() throws Exception {
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final var stream = new TerminalOutputStream(bytes -> {
            assertTrue(bytes.length <= PtyPeerOutput.MAX_BYTES);
            received.write(bytes);
            return new PtyPeerOutput.Receipt(bytes.length, 0);
        }, null, false);
        final byte[] bytes = new byte[300000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        stream.write(bytes, 0, bytes.length);
        stream.finish();
        assertArrayEquals(bytes, received.toByteArray());
        assertTrue(stream.completed);
        assertEquals(bytes.length, stream.sourceBytes);
        assertEquals(bytes.length, stream.bytesWritten);
    }

    @Test public void pngEncodingPreservesBytesAcrossAllChunkBoundaries() throws Exception {
        for (int size : new int[]{8, 3072, 3073, 6144, 95000}) {
            final byte[] png = new byte[size];
            final byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
            System.arraycopy(signature, 0, png, 0, signature.length);
            Arrays.fill(png, 8, size, (byte) 255);
            final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            final var stream = new TerminalOutputStream(bytes -> {
                encoded.write(bytes); return new PtyPeerOutput.Receipt(bytes.length, 0);
            }, "image/png", false);
            for (int i = 0; i < png.length; i++) stream.write(png, i, 1);
            stream.finish();
            final String wire = encoded.toString(StandardCharsets.US_ASCII.name());
            assertTrue(wire.startsWith("\033_Ga=T,f=100,q=2,C=1,m="));
            final ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            int offset = 0;
            while (offset < wire.length()) {
                final int sep = wire.indexOf(';', offset);
                final int end = wire.indexOf("\033\\", sep);
                assertTrue(end - sep - 1 <= 4096);
                decoded.write(Base64.getDecoder().decode(wire.substring(sep + 1, end)));
                assertTrue(wire.substring(offset, sep).endsWith(end + 2 == wire.length() ? "m=0" : "m=1"));
                offset = end + 2;
            }
            assertArrayEquals(png, decoded.toByteArray());
            assertEquals(png.length, stream.sourceBytes);
        }
    }

    @Test public void tmuxWrappingIsOnlyForPngAndDoesNotChangeServerSettings() throws Exception {
        final byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        final var stream = new TerminalOutputStream(bytes -> {
            encoded.write(bytes); return new PtyPeerOutput.Receipt(bytes.length, 0);
        }, "image/png", true);
        stream.write(png, 0, png.length); stream.finish();
        final String wire = encoded.toString(StandardCharsets.US_ASCII.name());
        assertTrue(wire.startsWith("\033Ptmux;\033\033_G"));
        assertTrue(wire.endsWith("\033\033\\\033\\"));
    }

    @Test public void invalidPngEmitsNothing() throws Exception {
        final var stream = new TerminalOutputStream(bytes -> { throw new AssertionError(); }, "image/png", false);
        stream.write(new byte[]{1, 2, 3}, 0, 3);
        assertThrows(IOException.class, stream::finish);
        assertEquals(0, stream.bytesWritten);
        assertFalse(stream.completed);
    }

    @Test public void partialAndUnconfirmedWritesNeverReplayOrFinish() throws Exception {
        final int[] calls = {0};
        final var partial = new TerminalOutputStream(bytes -> {
            calls[0]++; return new PtyPeerOutput.Receipt(4, 110);
        }, null, false);
        assertThrows(IOException.class, () -> partial.write(new byte[20], 0, 20));
        assertThrows(IOException.class, partial::finish);
        assertEquals(1, calls[0]);
        assertEquals(4, partial.bytesWritten);
        assertEquals(110, partial.errno);
        assertFalse(partial.completed);
        assertFalse(partial.writeUnconfirmed);
        final var unknown = new TerminalOutputStream(bytes -> { throw new IOException("disconnected"); }, null, false);
        assertThrows(IOException.class, () -> unknown.write(new byte[20], 0, 20));
        assertTrue(unknown.writeUnconfirmed);
        assertFalse(unknown.completed);
    }

    @Test public void emptyRawOutputSucceedsWithoutAnyWrites() throws Exception {
        final var stream = new TerminalOutputStream(bytes -> { throw new AssertionError(); }, null, false);
        stream.finish();
        assertTrue(stream.completed);
        assertEquals(0, stream.bytesWritten);
    }
}
