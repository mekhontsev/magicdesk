package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

public final class ClipboardAutomationGatewayTest {
    private static final int LIMIT = 262_144;

    @Test
    public void textLimitCannotSplitASupplementaryCharacter() throws Exception {
        final String prefix = "a".repeat(LIMIT - 1);
        final String text = prefix + "\ud83d\ude80";
        final var result = ClipboardAutomationGateway.describeText(
                read(AndroidClipboardGateway.Access.AVAILABLE, text));
        assertTrue(result.success);
        assertEquals(prefix, result.data.getString("text"));
        assertEquals(text.length(), result.data.getInt("textLength"));
        assertTrue(result.data.getBoolean("truncated"));
        final String returned = result.data.getString("text");
        assertEquals(returned, new String(returned.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
    }

    @Test
    public void exactLimitPreservesWholeCharactersAndWhitespace() throws Exception {
        final String text = " \t" + "a".repeat(LIMIT - 5) + "\ud83d\ude80\n";
        assertEquals(LIMIT, text.length());
        final var result = ClipboardAutomationGateway.describeText(
                read(AndroidClipboardGateway.Access.AVAILABLE, text));
        assertEquals(text, result.data.getString("text"));
        assertFalse(result.data.getBoolean("truncated"));
        assertTrue(result.data.getBoolean("sensitive"));
    }

    @Test
    public void unavailableClipboardDoesNotExposePayloadText() throws Exception {
        for (final var access : List.of(AndroidClipboardGateway.Access.DENIED,
                AndroidClipboardGateway.Access.FAILED, AndroidClipboardGateway.Access.UNAVAILABLE)) {
            final var result = ClipboardAutomationGateway.describeText(read(access, "private"));
            assertFalse(result.success);
            assertTrue(result.retryable);
            assertEquals(access.wireName, result.observation.getString("access"));
            assertFalse(result.observation.has("text"));
            assertFalse(result.data.has("text"));
        }
    }

    @Test
    public void emptyClipboardIsAnExplicitSuccessfulResult() throws Exception {
        final var result = ClipboardAutomationGateway.describeText(
                read(AndroidClipboardGateway.Access.EMPTY, ""));
        assertTrue(result.success);
        assertEquals("", result.data.getString("text"));
        assertEquals(0, result.data.getInt("textLength"));
        assertFalse(result.data.getBoolean("truncated"));
    }

    private static AndroidClipboardGateway.TextReadResult read(
            final AndroidClipboardGateway.Access access, final String text) {
        return new AndroidClipboardGateway.TextReadResult(new AndroidClipboardGateway.Metadata(
                access, 1, List.of("text/plain"), true, "", -1, ""), text);
    }
}
