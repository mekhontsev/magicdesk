package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class TermuxPtyProtocolTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef"
            + "0123456789abcdef0123456789abcdef";

    @Test
    public void readsOutputFrame() throws Exception {
        final byte[] source = frame(
                TermuxPtyProtocol.FRAME_OUTPUT,
                new byte[]{0x41, 0x42});

        final TermuxPtyProtocol.Frame result =
                TermuxPtyProtocol.readFrame(new DataInputStream(
                        new ByteArrayInputStream(source)));

        assertEquals(TermuxPtyProtocol.FRAME_OUTPUT, result.type);
        assertArrayEquals(new byte[]{0x41, 0x42}, result.payload);
    }

    @Test
    public void parsesAuthenticatedHello() throws Exception {
        final TermuxPtyProtocol.Frame frame = new TermuxPtyProtocol.Frame(
                TermuxPtyProtocol.FRAME_HELLO,
                (TOKEN + " 1234").getBytes(StandardCharsets.US_ASCII));

        assertEquals(
                1234L,
                TermuxPtyProtocol.parseHello(frame, TOKEN).processId);
    }

    @Test(expected = IOException.class)
    public void rejectsWrongHelloToken() throws Exception {
        final TermuxPtyProtocol.Frame frame = new TermuxPtyProtocol.Frame(
                TermuxPtyProtocol.FRAME_HELLO,
                (TOKEN + " 1234").getBytes(StandardCharsets.US_ASCII));

        TermuxPtyProtocol.parseHello(frame, TOKEN.substring(1) + "0");
    }

    @Test
    public void cleanEofHasNoFrame() throws Exception {
        assertNull(TermuxPtyProtocol.readFrame(new DataInputStream(
                new ByteArrayInputStream(new byte[0]))));
    }

    @Test
    public void rejectsOversizedFrame() {
        final byte[] source = new byte[]{
                (byte) TermuxPtyProtocol.FRAME_OUTPUT,
                0x00, 0x10, 0x00, 0x01
        };

        assertThrows(
                IOException.class,
                () -> TermuxPtyProtocol.readFrame(new DataInputStream(
                        new ByteArrayInputStream(source))));
    }

    @Test
    public void rejectsUnexpectedHelloFrame() {
        final TermuxPtyProtocol.Frame frame = new TermuxPtyProtocol.Frame(
                TermuxPtyProtocol.FRAME_OUTPUT,
                (TOKEN + " 12").getBytes(StandardCharsets.US_ASCII));

        assertThrows(
                IOException.class,
                () -> TermuxPtyProtocol.parseHello(frame, TOKEN));
    }

    private static byte[] frame(final int type, final byte[] payload)
            throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(type);
        output.writeInt(payload.length);
        output.write(payload);
        return bytes.toByteArray();
    }
}
