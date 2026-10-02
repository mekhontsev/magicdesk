package io.github.mekhontsev.magicdesk;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuestOperationTest {
    @Test public void outputIsBoundedAndCancellationDoesNotClaimCompletion() throws Exception {
        var operation = new GuestEnvironmentOperations.Operation("literal command", 2000);
        byte[] bytes = "x".repeat(70000).getBytes(StandardCharsets.UTF_8);
        operation.append(bytes, 0, bytes.length);
        var running = operation.snapshot();
        assertEquals(65536, running.getString("output").length());
        assertEquals(70000, running.getLong("outputBytes"));
        assertTrue(running.getBoolean("truncated"));
        operation.cancel();
        var cancelled = operation.observe(-1, 0);
        assertTrue(cancelled.getBoolean("cancelRequested"));
        assertEquals("running", cancelled.getString("state"));
        assertTrue(cancelled.isNull("exitCode"));
        assertFalse(cancelled.getBoolean("safeToRetry"));
        operation.done = true;
        assertEquals("cancelled", operation.snapshot().getString("state"));
        operation.exitCode = 0;
        assertEquals("completed", operation.snapshot().getString("state"));
    }
}
