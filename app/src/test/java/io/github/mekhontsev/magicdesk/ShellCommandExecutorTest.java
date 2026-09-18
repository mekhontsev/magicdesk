package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class ShellCommandExecutorTest {
    private static final String MARKER = "__MAGICDESK_TEST__";

    @Test public void binaryStdoutAndDelayedStderrAreSeparate() throws Exception {
        final ByteArrayOutputStream frames = new ByteArrayOutputStream();
        final byte[] binary = new byte[256];
        for (int i = 0; i < 256; i++) binary[i] = (byte) i;
        frame(frames, 33, binary);
        frame(frames, 33, end(7));
        frame(frames, 34, "late diagnostic\n");
        frame(frames, 34, end(7));
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final var result = ShellCommandOutput.read(new ByteArrayInputStream(frames.toByteArray()),
                MARKER, received::write);
        assertArrayEquals(binary, received.toByteArray());
        assertEquals("", result.output());
        assertEquals("late diagnostic\n", result.stderr());
        assertEquals(7, result.exitCode());
        assertEquals("/tmp", result.workingDirectory());
    }

    @Test public void everyByteCanBeItsOwnFrameIncludingUtf8AndMarkers() throws Exception {
        final ByteArrayOutputStream frames = new ByteArrayOutputStream();
        final String text = "one\ntwo\n\u043f\u0440\u0438\u0432\u0435\u0442\n";
        for (byte value : (text + end(0)).getBytes(StandardCharsets.UTF_8)) frame(frames, 33, new byte[]{value});
        frame(frames, 34, end(0));
        final var result = read(frames);
        assertEquals(text, result.output());
    }

    @Test public void rejectedMarkerLineAndPartialPrefixAreOrdinaryOutput() throws Exception {
        final String text = "before\n__MAGIC\n" + MARKER + "not-a-record";
        final ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frame(frames, 33, text + end(0));
        frame(frames, 34, end(0));
        assertEquals(text, read(frames).output());
    }

    @Test public void ordinaryCommandsRetainBothOutputChannels() throws Exception {
        final ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frame(frames, 34, "error");
        frame(frames, 33, "out");
        frame(frames, 34, end(0));
        frame(frames, 33, end(0));
        assertEquals("errorout", read(frames).output());
    }

    @Test public void malformedOrMissingBoundariesAndFramesAreRejected() throws Exception {
        final ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frame(frames, 33, end(0));
        assertThrows(IOException.class, () -> read(frames));
        frame(frames, 34, end(1));
        assertThrows(IOException.class, () -> read(frames));
        final ByteArrayOutputStream invalid = new ByteArrayOutputStream();
        frame(invalid, 98, "x");
        assertThrows(IOException.class, () -> read(invalid));
        invalid.reset();
        frame(invalid, 33, new byte[8193]);
        assertThrows(IOException.class, () -> read(invalid));
    }

    @Test public void captureIsBoundedButRedirectedStdoutIsNotTruncated() throws Exception {
        final ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (int i = 0; i < 64; i++) {
            frame(frames, 33, new byte[8192]);
            frame(frames, 34, new byte[8192]);
        }
        frame(frames, 33, end(0)); frame(frames, 34, end(0));
        final long[] count = {0};
        final var result = ShellCommandOutput.read(new ByteArrayInputStream(frames.toByteArray()), MARKER,
                (b, o, n) -> count[0] += n);
        assertEquals(524288, count[0]);
        assertTrue(result.stderrTruncated());
        assertEquals("", result.output());
        assertTrue(result.stderr().length() < 400000);
    }

    @Test public void recipientFailurePreservesObservedStderrAndStopsReading() throws Exception {
        final ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frame(frames, 34, "diagnostic");
        frame(frames, 33, "content");
        final var error = assertThrows(ShellCommandOutput.Failure.class,
                () -> ShellCommandOutput.read(new ByteArrayInputStream(frames.toByteArray()), MARKER,
                        (b, o, n) -> { throw new IOException("recipient closed"); }));
        assertEquals("diagnostic", error.stderr);
        assertEquals("recipient closed", error.getMessage());
    }

    private static ShellCommandOutput.Result read(ByteArrayOutputStream frames) throws IOException {
        return ShellCommandOutput.read(new ByteArrayInputStream(frames.toByteArray()), MARKER, null);
    }
    private static String end(int code) { return "\n" + MARKER + code + "\t/tmp\n"; }
    private static void frame(ByteArrayOutputStream output, int kind, String bytes) throws IOException {
        frame(output, kind, bytes.getBytes(StandardCharsets.UTF_8));
    }
    private static void frame(ByteArrayOutputStream output, int kind, byte[] bytes) throws IOException {
        final DataOutputStream data = new DataOutputStream(output);
        data.writeByte(kind); data.writeInt(bytes.length); data.write(bytes);
    }
}
