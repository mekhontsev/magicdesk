package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.List;

import org.junit.Test;

public final class AndroidContentPayloadTest {
    @Test
    public void htmlOnlyClipRetainsTheRequiredEmptyPlainTextSlot() throws Exception {
        final var content = AndroidContentPayload.create(AndroidContentPayload.Origin.INTENT,
                "HTML", "", "", "<b>content</b>", List.of(), List.of("text/html"), false);
        assertTrue(content.hasText());
        assertTrue(content.canShare());
        assertEquals("", content.text);
        assertEquals("<b>content</b>", content.htmlText);
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/AndroidContentPayload.java"));
        final String conversion = source.substring(source.indexOf("ClipData toClipData()"));
        assertTrue(conversion.contains("final CharSequence clipText = hasText() ? text : null;"));
        assertTrue(conversion.contains("new ClipData.Item(clipText, clipHtml, null, firstUri)"));
    }

    @Test
    public void incomingStreamListIsBoundedBeforeTraversal() {
        final var streams = new StreamList(Integer.MAX_VALUE);
        final var content = AndroidContentPayload.mergeSendContent(
                clip(), streams, "image/png", "Subject", "Shared text", "");
        assertEquals(64, streams.reads);
        assertTrue(content.truncated);
        assertEquals("Shared text", content.text);
    }

    @Test
    public void exactStreamLimitIsNotReportedAsTruncation() {
        final var streams = new StreamList(64);
        final var content = AndroidContentPayload.mergeSendContent(
                clip(), streams, null, "", "", "");
        assertEquals(64, streams.reads);
        assertFalse(content.truncated);
    }

    @Test
    public void mergingKeepsClipContentSensitivityAndIntentMetadata() {
        final var content = AndroidContentPayload.mergeSendContent(
                clip(), null, "text/html", "Subject", "", "<b>shared</b>");
        assertEquals(AndroidContentPayload.Origin.INTENT, content.origin);
        assertEquals("Subject", content.label);
        assertEquals("Subject", content.subject);
        assertEquals("Clip text", content.text);
        assertEquals("<b>shared</b>", content.htmlText);
        assertTrue(content.sensitive);
        assertFalse(content.truncated);
    }

    @Test
    public void mergingPreservesPreviousTruncationAndLabel() {
        final var truncated = AndroidContentPayload.mergeSendContent(
                clip(), new StreamList(65), null, "", "", "");
        final var content = AndroidContentPayload.mergeSendContent(
                truncated, null, null, "", "", "");
        assertTrue(content.truncated);
        assertEquals("Clip label", content.label);
    }

    @Test
    public void localDragRejectsOversizedSelectionBeforeReadingUris() {
        final List<AndroidContentPayload.UriItem> items = new AbstractList<>() {
            @Override
            public AndroidContentPayload.UriItem get(final int index) {
                throw new AssertionError("oversized drag must not be traversed");
            }

            @Override
            public int size() {
                return AndroidContentPayload.MAX_URI_ITEMS + 1;
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> AndroidContentPayload.drag("Selection", items, FileDragPayload.MIME_TYPE));
    }

    @Test
    public void localDragWithoutUrisKeepsLocalTransportAndLabel() {
        final var content = AndroidContentPayload.drag("Selection", List.of(), FileDragPayload.MIME_TYPE);
        assertEquals("Selection", content.text);
        assertFalse(content.hasUris());
        assertTrue(content.mimeTypes.description.contains(FileDragPayload.MIME_TYPE));
    }

    @Test
    public void sendMergePreservesDeclarationsWithoutPromotingDerivedTextMetadata() {
        final var clip = AndroidContentPayload.create(
                AndroidContentPayload.Origin.CLIPBOARD, "Clip", "", "Text", "<b>Text</b>",
                List.of(), List.of(), false);
        final var merged = AndroidContentPayload.mergeSendContent(
                clip, List.of(), "image/png", "", "", "");
        assertEquals(List.of("image/png"), merged.mimeTypes.declared);
        assertTrue(merged.mimeTypes.description.contains("text/plain"));
        assertTrue(merged.mimeTypes.description.contains("text/html"));
        assertEquals("text/html", merged.preferredMimeType());
    }

    private static AndroidContentPayload clip() {
        return AndroidContentPayload.text("Clip label", "Clip text", true,
                AndroidContentPayload.Origin.CLIPBOARD);
    }

    private static final class StreamList extends AbstractList<Uri> {
        private final int count;
        int reads;

        StreamList(final int count) {
            this.count = count;
        }

        @Override
        public Uri get(final int index) {
            if (index >= 64) {
                throw new AssertionError("stream traversal exceeded the content limit");
            }
            reads++;
            return null;
        }

        @Override
        public int size() {
            return count;
        }
    }
}
