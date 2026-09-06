package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Keeps Android callback identity and ownership at the Termux boundary. */
public final class TermuxResultContractTest {
    @Test
    public void pendingIntentIdentitySurvivesProcessRestartsWithoutIdReuse()
            throws Exception {
        final String source = source();
        assertTrue(source.contains("UUID.randomUUID()"));
        assertTrue(source.contains(".setData(Uri.parse(requestId))"));
        assertTrue(source.contains("intent.getDataString()"));
        assertTrue(source.contains("PendingIntent.FLAG_ONE_SHOT"));
        assertTrue(source.contains("PendingIntent.FLAG_MUTABLE"));
        assertFalse(source.contains("AtomicInteger"));
        assertFalse(source.contains("getIntExtra("));
    }

    @Test
    public void registrationOwnsTheTimerAndPendingIntentUntilTaken()
            throws Exception {
        final String source = source();
        assertTrue(source.indexOf("PendingIntent.getBroadcast(")
                < source.indexOf("PENDING.put("));
        assertTrue(source.contains("take(registration.requestId)"));
        assertTrue(source.contains("final Registration pending = take(requestId)"));
        assertTrue(source.contains("MAIN.removeCallbacks(pending.timeout)"));
        assertTrue(source.contains("pending.pendingIntent.cancel()"));
    }

    private static String source() throws Exception {
        return Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "TermuxCommandResultReceiver.java"));
    }
}
