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
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    @Test
    public void parsesForegroundProcess() throws Exception {
        final byte[] name = "nvim".getBytes(StandardCharsets.UTF_8);
        final byte[] payload = new byte[8 + name.length];
        payload[2] = 0x04;
        payload[3] = (byte) 0xD2;
        payload[6] = 0x04;
        payload[7] = (byte) 0xD2;
        System.arraycopy(name, 0, payload, 8, name.length);

        final TerminalProcessInfo process =
                TermuxPtyProtocol.parseForegroundProcess(
                        new TermuxPtyProtocol.Frame(
                                TermuxPtyProtocol.FRAME_FOREGROUND_PROCESS,
                                payload));

        assertEquals(1234L, process.processId);
        assertEquals(1234L, process.processGroupId);
        assertEquals("nvim", process.executable);
    }

    @Test
    public void acceptsUnavailableForegroundProcess() throws Exception {
        final TerminalProcessInfo process =
                TermuxPtyProtocol.parseForegroundProcess(
                        new TermuxPtyProtocol.Frame(
                                TermuxPtyProtocol.FRAME_FOREGROUND_PROCESS,
                                new byte[8]));

        assertEquals(false, process.isKnown());
    }

    @Test
    public void fragmentedHandshakeSharesOneDeadline() throws Exception {
        final byte[] hello = frame(TermuxPtyProtocol.FRAME_HELLO,
                (TOKEN + " 1234").getBytes(StandardCharsets.US_ASCII));
        final FragmentedSocket socket = new FragmentedSocket(hello, 100);

        assertThrows(SocketTimeoutException.class,
                () -> TermuxPtyProtocol.readHello(socket, TOKEN, 500, socket.time::get));

        assertEquals(List.of(500, 400, 300, 200, 100), socket.timeouts);
    }

    @Test
    public void timelyHandshakeDoesNotConsumeFollowingOutput() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(frame(TermuxPtyProtocol.FRAME_HELLO,
                (TOKEN + " 1234").getBytes(StandardCharsets.US_ASCII)));
        bytes.write(frame(TermuxPtyProtocol.FRAME_OUTPUT, new byte[]{65}));
        final FragmentedSocket socket = new FragmentedSocket(bytes.toByteArray(), 1);

        assertEquals(1234L, TermuxPtyProtocol.readHello(
                socket, TOKEN, 1000, socket.time::get).processId);
        assertArrayEquals(new byte[]{65}, TermuxPtyProtocol.readFrame(
                new DataInputStream(socket.getInputStream())).payload);
    }

    @Test
    public void handshakeRejectsOversizedPayloadBeforeReadingIt() throws Exception {
        final FragmentedSocket socket = new FragmentedSocket(
                new byte[]{17, 0, 0, 1, 0}, 0);

        final IOException failure = assertThrows(IOException.class,
                () -> TermuxPtyProtocol.readHello(socket, TOKEN, 1000, socket.time::get));
        assertEquals("invalid Termux PTY handshake frame", failure.getMessage());
        assertEquals(5, socket.timeouts.size());
    }

    private static final class FragmentedSocket extends Socket {
        final AtomicLong time = new AtomicLong();
        final List<Integer> timeouts = new ArrayList<>();
        private final InputStream input;

        FragmentedSocket(final byte[] bytes, final int millisPerRead) {
            input = new ByteArrayInputStream(bytes) {
                @Override
                public synchronized int read(final byte[] target, final int offset,
                        final int length) {
                    time.addAndGet(TimeUnit.MILLISECONDS.toNanos(millisPerRead));
                    return super.read(target, offset, Math.min(1, length));
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public void setSoTimeout(final int timeout) {
            timeouts.add(timeout);
        }
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
