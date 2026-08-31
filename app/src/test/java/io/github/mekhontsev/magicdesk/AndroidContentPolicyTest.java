package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public final class AndroidContentPolicyTest {
    @Test
    public void itemMimeTypeWinsOverTransportDeclarations() {
        assertEquals(
                "text/plain",
                AndroidContentPayload.selectPreferredMimeType(
                        List.of("text/plain"),
                        List.of("text/uri-list", "application/octet-stream"),
                        true,
                        false));
    }

    @Test
    public void mixedUriMimeTypesUseWildcard() {
        assertEquals(
                "*/*",
                AndroidContentPayload.selectPreferredMimeType(
                        List.of("image/png", "image/jpeg"),
                        List.of("text/uri-list"),
                        true,
                        false));
    }

    @Test
    public void textPayloadKeepsHtmlSemantics() {
        assertEquals(
                "text/html",
                AndroidContentPayload.selectPreferredMimeType(
                        List.of(), List.of(), false, true));
    }

    @Test
    public void clipboardTextFileNameUsesSubjectAndExtension() {
        assertEquals(
                "Meeting notes.txt",
                ContentUriTransfer.textFileName(
                        "Meeting notes", "Clipboard", false));
    }

    @Test
    public void invalidClipboardTextFileNameFallsBack() {
        assertEquals(
                "Clipboard text.html",
                ContentUriTransfer.textFileName(
                        "../", "", true));
    }
}
