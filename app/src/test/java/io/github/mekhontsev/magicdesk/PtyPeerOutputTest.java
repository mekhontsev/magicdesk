package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;

public final class PtyPeerOutputTest {
    @Test public void payloadKeepsBinaryAndTerminalControls() {
        final byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        assertArrayEquals(bytes, PtyPeerOutput.payload(null, Base64.getEncoder().encodeToString(bytes)));
        final String text = "\u001b[31mhello\u0000\ud83d\ude80\u0007";
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), PtyPeerOutput.payload(text, null));
    }

    @Test public void payloadIsExclusiveAndBoundedBeforeDecode() {
        assertThrows(IllegalArgumentException.class, () -> PtyPeerOutput.payload(null, null));
        assertThrows(IllegalArgumentException.class, () -> PtyPeerOutput.payload("x", "eA=="));
        assertThrows(IllegalArgumentException.class, () -> PtyPeerOutput.payload("", null));
        assertThrows(IllegalArgumentException.class, () -> PtyPeerOutput.payload(null, "***"));
        assertEquals(65536, PtyPeerOutput.payload("x".repeat(65536), null).length);
        assertThrows(IllegalArgumentException.class, () -> PtyPeerOutput.payload("x".repeat(65537), null));
        assertThrows(IllegalArgumentException.class, () -> PtyPeerOutput.payload("\u20ac".repeat(30000), null));
        assertThrows(IllegalArgumentException.class, () -> PtyPeerOutput.payload(null, "A".repeat(100000)));
    }

    @Test public void endpointDoesNotAcceptArbitraryFilesOrIncompleteIdentity() throws Exception {
        final var endpoint = PtyEndpoint.parse("123 456 /dev/pts/7");
        assertEquals(123, endpoint.processId());
        assertEquals(456, endpoint.startTicks());
        assertEquals("123 456 /dev/pts/7", endpoint.wireValue());
        for (String value : new String[]{"123", "123 0 /dev/pts/7", "1 2 /tmp/file", "1 2 /dev/pts/../x", "1 2 /dev/pts/7;exit"}) {
            assertThrows(IOException.class, () -> PtyEndpoint.parse(value));
        }
    }

    @Test public void receiptDistinguishesSuccessPartialAndUnknown() throws Exception {
        assertEquals(5, PtyPeerOutput.Receipt.parse("MAGICDESK_EMIT 5 0\n", 5).bytesWritten());
        final var partial = PtyPeerOutput.Receipt.parse("MAGICDESK_EMIT 3 110\n", 5);
        final var failed = DesktopAutomationPtyOutput.result(partial, new JSONObject());
        assertFalse(failed.success);
        assertFalse(failed.retryable);
        assertEquals(3, failed.observation.getInt("bytesWritten"));
        assertFalse(failed.observation.getBoolean("operationMayContinue"));
        assertFalse(failed.observation.getBoolean("safeToRetry"));
        for (String value : new String[]{"", "MAGICDESK_EMIT 3 0", "MAGICDESK_EMIT 6 1", "MAGICDESK_EMIT -1 2", "x 5 0"}) {
            assertThrows(IOException.class, () -> PtyPeerOutput.Receipt.parse(value, 5));
        }
    }

    @Test public void commandTransportsBytesRatherThanExecutingThem() {
        final String dangerous = "$(touch /tmp/should-not-exist); ' \n\u001b\\";
        final String command = PtyPeerOutput.emitCommand(new PtyEndpoint(1, 2, "/dev/pts/3"),
                dangerous.getBytes(StandardCharsets.UTF_8));
        assertFalse(command.contains("touch"));
        assertTrue(command.contains("--emit-pty 1 2 '/dev/pts/3'"));
    }
}
