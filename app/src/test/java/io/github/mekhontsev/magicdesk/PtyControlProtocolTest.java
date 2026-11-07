package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

public final class PtyControlProtocolTest {
    @Test public void sharedHandshakeRetainsEndpointAndDoesNotConsumeOutput() throws Exception {
        var bytes = new java.io.ByteArrayInputStream(
                "MAGICDESK_PTY 123 456 /dev/pts/7\nhello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.junit.Assert.assertEquals(new PtyEndpoint(123, 456, "/dev/pts/7"), PtyControlProtocol.readEndpoint(bytes));
        org.junit.Assert.assertEquals('h', bytes.read());
        for (String bad : new String[]{"", "MAGICDESK_PTY 123 456 /dev/pts/7",
                "MAGICDESK_PTY 0 456 /dev/pts/7\n", "x".repeat(193) + "\n"}) {
            org.junit.Assert.assertThrows(java.io.IOException.class, () -> PtyControlProtocol.readEndpoint(
                    new java.io.ByteArrayInputStream(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
    }

    @Test
    public void dataFrameUsesNetworkOrderLength() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PtyControlProtocol.writeData(
                new DataOutputStream(bytes), new byte[]{0x41, 0x42});

        assertArrayEquals(
                new byte[]{1, 0, 0, 0, 2, 0x41, 0x42},
                bytes.toByteArray());
    }

    @Test
    public void resizeFrameCarriesRowsThenColumns() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PtyControlProtocol.writeResize(
                new DataOutputStream(bytes), 24, 80);

        assertArrayEquals(
                new byte[]{2, 0, 0, 0, 8,
                        0, 0, 0, 24,
                        0, 0, 0, 80},
                bytes.toByteArray());
    }

    @Test
    public void workingDirectoryRequestHasNoPayload() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PtyControlProtocol.writeWorkingDirectoryRequest(
                new DataOutputStream(bytes));

        assertArrayEquals(
                new byte[]{3, 0, 0, 0, 0},
                bytes.toByteArray());
    }

    @Test
    public void foregroundProcessRequestHasNoPayload() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PtyControlProtocol.writeForegroundProcessRequest(
                new DataOutputStream(bytes));

        assertArrayEquals(
                new byte[]{4, 0, 0, 0, 0},
                bytes.toByteArray());
    }
}
