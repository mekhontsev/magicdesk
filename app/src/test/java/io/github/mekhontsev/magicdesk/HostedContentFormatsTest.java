package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class HostedContentFormatsTest {
    @Test public void dragDistinguishesInlineTextFromAPlainTextFile() {
        assertEquals(List.of("UTF8_STRING", "text/plain;charset=utf-8", "text/plain", "text/html"),
                X11ContentExchange.FORMATS.dragTypes(List.of("text/plain", "text/html")));
        assertEquals(List.of("text/uri-list"), HostedContentFormats.MIME.dragTypes(List.of("text/plain", "text/uri-list")));
        assertEquals(List.of("image/png", "text/uri-list"), HostedContentFormats.MIME.dragTypes(List.of("image/png", "text/uri-list")));
        assertEquals(List.of(), HostedContentFormats.MIME.dragTypes(List.of()));
    }
    @Test public void prefersUtf8AndDoesNotInventAFormat() {
        assertEquals("UTF8_STRING", X11ContentExchange.FORMATS.textType(List.of("STRING", "UTF8_STRING")));
        assertEquals("STRING", X11ContentExchange.FORMATS.textType(List.of("STRING")));
        assertNull(HostedContentFormats.MIME.textType(List.of("STRING", "UTF8_STRING")));
        assertEquals("text/plain;charset=utf-8", HostedContentFormats.MIME.textType(List.of("text/plain", "text/plain;charset=utf-8")));
        assertNull(HostedContentFormats.MIME.textType(List.of("image/png")));
    }
    @Test public void fileListSupportsCommentsEscapesAndDeduplication() {
        assertEquals(List.of("file:///tmp/a%20b", "file://localhost/tmp/c"), HostedContentFormats.files(
                "# files\r\nfile:///tmp/a%20b\r\n\r\nfile://localhost/tmp/c\nfile:///tmp/a%20b\n"));
        assertEquals("a b", HostedContentFormats.fileName("file:///tmp/a%20b"));
    }
    @Test public void rejectsRemoteFilesAndExecutableOrProviderUris() {
        for (String value : List.of("https://example.org/a", "file://server/tmp/a", "file:relative", "content://other/a",
                "file:///tmp/a?query", "file:///tmp/a#fragment", "file:///tmp/a%00b")) {
            assertThrows(IllegalArgumentException.class, () -> HostedContentFormats.files(value));
        }
    }
    @Test public void boundsNumberOfFiles() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 65; i++) input.append("file:///tmp/").append(i).append('\n');
        assertThrows(IllegalArgumentException.class, () -> HostedContentFormats.files(input.toString()));
    }
}
